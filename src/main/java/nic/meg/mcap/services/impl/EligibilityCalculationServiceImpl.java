package nic.meg.mcap.services.impl;

import lombok.RequiredArgsConstructor;
import nic.meg.mcap.dto.response.EligibilityListRowDTO;
import nic.meg.mcap.dto.response.EligibilityResultResponseDTO;
import nic.meg.mcap.entities.*;
import nic.meg.mcap.enums.ApplicantType;
import nic.meg.mcap.enums.ApplicationPool;
import nic.meg.mcap.enums.CalculationType;
import nic.meg.mcap.enums.ScoreSource;
import nic.meg.mcap.repositories.AcademicRecordRepository;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.repositories.EligibilityCriteriaRepository;
import nic.meg.mcap.repositories.EligibilityResultRepository;
import nic.meg.mcap.repositories.AdmissionWindowRepository;
import nic.meg.mcap.services.EligibilityCalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EligibilityCalculationServiceImpl implements EligibilityCalculationService {

    private final AcademicRecordRepository academicRecordRepo;
    private final EligibilityCriteriaRepository criteriaRepo;
    private final EligibilityResultRepository resultRepo;
    private final ApplicationRepository applicationRepository;
    private final AdmissionWindowRepository admissionWindowRepository;

    @Override
    @Transactional
    public void calculateAndSaveEligibility(Application applicationInput) {
        Application application = applicationRepository.findById(applicationInput.getApplicationId())
                .orElseThrow(() -> new RuntimeException("Application not found"));

        Applicant applicant = application.getApplicant();

        resultRepo.deleteByApplication_ApplicationId(application.getApplicationId());

        // Extract unique BASE programmes from the ProgrammeOffered structure.
        // This ensures we only calculate eligibility once per base programme,
        // even if the applicant applied to multiple shifts.
        Set<Programme> uniqueProgrammes = new HashSet<>();
        if (application.getApplicantProgrammePreferences() != null) {
            for (ApplicantProgrammePreference pref : application.getApplicantProgrammePreferences()) {
                if (pref.getProgrammeOffered() != null && pref.getProgrammeOffered().getProgramme() != null) {
                    uniqueProgrammes.add(pref.getProgrammeOffered().getProgramme());
                }
            }
        }

        for (Programme programme : uniqueProgrammes) {
            checkEligibilityForProgramme(application, applicant, programme);
        }

        resultRepo.flush();
    }

    private void checkEligibilityForProgramme(Application application, Applicant applicant, Programme programme) {
        String admissionCode = application.getAdmissionWindow().getAdmissionCode();
        Short programmeId = programme.getProgrammeId();

        EligibilityCriteria criteria = criteriaRepo
                .findByAdmissionWindowAdmissionCodeAndProgrammeProgrammeId(admissionCode, programmeId)
                .orElse(null);

        if (criteria == null) {
            saveResult(application, programme, false, false, "Eligibility Criteria not configured.");
            return;
        }

        // ── REGULAR pool gate ─────────────────────────────────────────────────
        // Eligibility calculation only runs for REGULAR pool applications.
        // LATE pool applicants are excluded entirely.
        // NOTE: applicantPool (REGULAR/LATE) lives on Application, not applicantType.
        if (!ApplicationPool.REGULAR.equals(application.getApplicantPool())) {
            saveResult(application, programme, false, false, "Application is not in the REGULAR pool.");
            return;
        }
        // ── End REGULAR pool gate ─────────────────────────────────────────────

        double relaxation = resolveRelaxation(criteria, applicant);

        // ── Qualification Gate (OR logic, per-qualification min %) ────────────
        // If qualificationRequirements is non-empty, the applicant must hold at
        // least one of the listed qualifications AND meet THAT qualification's
        // own minimum overall percentage (stored field, never computed from subjects).
        // Empty list = no gate, all pass.
        List<EligibilityQualificationRequirement> qualRequirements = criteria.getQualificationRequirements();
        List<AcademicRecord> allRecords = academicRecordRepo.findAllByApplicant(applicant);

        AcademicRecord qualRecord = null;

        if (qualRequirements != null && !qualRequirements.isEmpty()) {
            // Build lookup by qualificationLevel name (lowercase) -> record
            Map<String, AcademicRecord> recordByQualName = new LinkedHashMap<>();
            for (AcademicRecord r : allRecords) {
                if (r.getQualificationLevel() == null) continue;
                recordByQualName.putIfAbsent(r.getQualificationLevel().trim().toLowerCase(), r);
            }

            EligibilityQualificationRequirement matchedReq = null;
            String lastFailureReason = null;

            for (EligibilityQualificationRequirement qr : qualRequirements) {
                Qualification q = qr.getQualification();
                if (q == null || q.getName() == null) continue;

                String reqQualName = q.getName().trim().toLowerCase();

                // PRIMARY MATCH: applicant's qualificationLevel matches the required name exactly.
                AcademicRecord candidate = recordByQualName.get(reqQualName);

                // STREAM FALLBACK:
                // Qualification names may be in the form "Class XII or Equivalent (Arts)".
                // We extract the base level token (e.g. "class xii") and the stream hint
                // from the suffix, stripping "or equivalent" and parentheses so we get
                // just the clean stream keyword (e.g. "arts").
                //
                // The stream hint is then matched against the applicant's AcademicRecord via:
                //   1. stream_id → linked Stream entity (r.getStream().getStreamName())
                //   2. stream_or_major → free-text field
                //   3. qualification_level itself (may embed stream in its text)
                if (candidate == null) {
                    String streamHint = null;
                    String baseLevel = reqQualName;
                    for (String levelToken : new String[]{"class xii", "class 12", "xii", "12th", "hslc", "hsslc"}) {
                        if (reqQualName.startsWith(levelToken)) {
                            String suffix = reqQualName.substring(levelToken.length()).trim();
                            if (!suffix.isBlank()) {
                                // Strip "or equivalent", parentheses, and extra whitespace
                                // e.g. "or equivalent (arts)" → "arts"
                                String cleaned = suffix
                                        .replaceAll("or equivalent", "")
                                        .replaceAll("[()]", "")
                                        .trim();
                                if (!cleaned.isBlank()) {
                                    streamHint = cleaned;
                                    baseLevel = levelToken;
                                }
                            }
                            break;
                        }
                    }

                    if (streamHint != null) {
                        final String finalStreamHint = streamHint;
                        final String finalBaseLevel = baseLevel;
                        candidate = allRecords.stream()
                                .filter(r -> {
                                    if (r.getQualificationLevel() == null) return false;
                                    String ql = r.getQualificationLevel().trim().toLowerCase();

                                    // The record's qualification_level must be at the same base level
                                    // (e.g. starts with "class xii").
                                    if (!ql.startsWith(finalBaseLevel)) return false;

                                    // 1. Check stream_id → Stream entity name (preferred — structured data)
                                    if (r.getStream() != null && r.getStream().getStreamName() != null
                                            && r.getStream().getStreamName().trim().toLowerCase()
                                            .contains(finalStreamHint)) {
                                        return true;
                                    }

                                    // 2. Check stream_or_major free-text field
                                    if (r.getStreamOrMajor() != null
                                            && r.getStreamOrMajor().trim().toLowerCase()
                                            .contains(finalStreamHint)) {
                                        return true;
                                    }

                                    // 3. Check qualification_level itself (may embed stream,
                                    //    e.g. "Class XII or Equivalent (Arts)")
                                    if (ql.contains(finalStreamHint)) {
                                        return true;
                                    }

                                    return false;
                                })
                                .findFirst()
                                .orElse(null);
                    }
                }

                if (candidate == null) continue;

                if (qr.getMinPercentage() != null) {
                    // Use the stored overall percentage ONLY.
                    // Do NOT compute from subject marks — the stored percentage field
                    // is the authoritative overall result as entered by the applicant.
                    double pct = candidate.getPercentage() != null ? candidate.getPercentage() : 0.0;
                    if (pct <= 0.0) {
                        lastFailureReason = String.format(
                                "%s: stored overall percentage is missing or zero.",
                                q.getName());
                        continue;
                    }
                    double required = qr.getMinPercentage() - relaxation;
                    if (pct < required) {
                        lastFailureReason = String.format(
                                "%s: %.2f%% < required %.2f%% (incl. %.1f%% relaxation)",
                                q.getName(), pct, required, relaxation);
                        continue;
                    }
                }

                matchedReq = qr;
                qualRecord = candidate;
                break;
            }

            if (matchedReq == null) {
                String accepted = qualRequirements.stream()
                        .map(qr -> qr.getQualification().getName()
                                + (qr.getMinPercentage() != null ? " (min " + qr.getMinPercentage() + "%)" : ""))
                        .collect(java.util.stream.Collectors.joining(" OR "));
                String reason = lastFailureReason != null
                        ? lastFailureReason
                        : "Does not hold any of the required qualifications: " + accepted;
                saveResult(application, programme, false, false, reason);
                return;
            }
        }

        // If no qualification gate matched a specific record, fall back to the
        // applicant's latest qualification record (or the first one available).
        // The overall percentage check below uses this record's stored percentage.
        if (qualRecord == null && !allRecords.isEmpty()) {
            qualRecord = allRecords.stream()
                    .filter(r -> Boolean.TRUE.equals(r.isLatestQualification()))
                    .findFirst()
                    .orElse(allRecords.get(0));
        }

        List<SubjectMark> studentMarks = (qualRecord != null && qualRecord.getSubjectMarks() != null)
                ? qualRecord.getSubjectMarks()
                : List.of();

        // ── CUET gate ─────────────────────────────────────────────────────────
        // Only enforce the CUET gate for WITH_ENTRANCE (CUET) applicants, and only
        // for the CUET-eligibility computation below. It never blocks NON-CUET
        // eligibility, since the NON-CUET round doesn't look at CUET data at all.
        boolean isWithoutEntrance = ApplicantType.WITHOUT_ENTRANCE.name()
                .equals(application.getApplicantType() != null ? application.getApplicantType().name() : "");

        boolean missingRequiredCuetData = criteria.isCuetRequired()
                && applicant.getCuetScore() == null
                && !isWithoutEntrance;
        // ── End CUET gate ─────────────────────────────────────────────────────

        List<EligibilityRuleSet> ruleSets = criteria.getRuleSets() != null ? criteria.getRuleSets() : List.of();
        if (ruleSets.isEmpty()) {
            // No rule sets configured at all = base qualification criteria alone
            // decides. Missing CUET data still blocks the CUET flag specifically.
            boolean cuetOk = !isWithoutEntrance && !missingRequiredCuetData;
            saveResult(application, programme, cuetOk, true,
                    missingRequiredCuetData
                            ? "CUET is required but CUET data is missing. (NON-CUET: Eligible — base qualification criteria satisfied.)"
                            : "Eligible (Base qualification criteria satisfied).");
            return;
        }

        // Two independent OR-across-rulesets passes:
        //   - CUET pass:    WITH_ENTRANCE only, and ONLY rule sets that are 100%
        //                    CUET-sourced requirements — no NON-CUET fallback.
        //                    This is the ONLY flag CUET-round merit list matching
        //                    should ever read.
        //   - NON-CUET pass: any rule set that is NOT 100% CUET-sourced (i.e. pure
        //                    NON-CUET or mixed). Runs for every applicant type and
        //                    never requires CUET data — used for NON-CUET round
        //                    matching and for CUET-to-NON-CUET carryover.
        boolean cuetEligible = false;
        boolean nonCuetEligible = false;
        StringBuilder cuetReason = new StringBuilder();
        StringBuilder nonCuetReason = new StringBuilder();

        if (missingRequiredCuetData) {
            cuetReason.append("CUET is required but CUET data is missing. ");
        }

        for (EligibilityRuleSet rs : ruleSets) {
            if (rs == null) continue;

            List<SubjectRequirement> reqs = rs.getSubjectRequirements();
            boolean allCuet = reqs != null && !reqs.isEmpty()
                    && reqs.stream().allMatch(r -> r != null && r.getScoreSource() == ScoreSource.CUET);

            String description = rs.getDescription() != null ? rs.getDescription() : "RuleSet";

            // CUET pass: only pure-CUET rule sets count, only for WITH_ENTRANCE,
            // only when we actually have CUET data to evaluate.
            if (!cuetEligible && !isWithoutEntrance && allCuet && !missingRequiredCuetData) {
                StringBuilder pathReason = new StringBuilder();
                boolean pass = checkRuleSet(rs, applicant, studentMarks, qualRecord, relaxation, pathReason);
                if (pass) {
                    cuetEligible = true;
                    cuetReason.setLength(0);
                    cuetReason.append("Eligible (Satisfied rule: ").append(description).append(").");
                } else if (pathReason.length() > 0) {
                    cuetReason.append("Rule failed (").append(description).append("): ").append(pathReason).append(" | ");
                }
            }

            // NON-CUET pass: any rule set that isn't pure-CUET-only counts, for
            // every applicant type, regardless of CUET data availability.
            if (!nonCuetEligible && !allCuet) {
                StringBuilder pathReason = new StringBuilder();
                boolean pass = checkRuleSet(rs, applicant, studentMarks, qualRecord, relaxation, pathReason);
                if (pass) {
                    nonCuetEligible = true;
                    nonCuetReason.setLength(0);
                    nonCuetReason.append("Eligible (Satisfied rule: ").append(description).append(").");
                } else if (pathReason.length() > 0) {
                    nonCuetReason.append("Rule failed (").append(description).append("): ").append(pathReason).append(" | ");
                }
            }
        }

        String combinedReason = "CUET: " + (cuetReason.length() > 0 ? cuetReason.toString().trim() : "N/A")
                + "  |  NON-CUET: " + (nonCuetReason.length() > 0 ? nonCuetReason.toString().trim() : "N/A");

        saveResult(application, programme, cuetEligible, nonCuetEligible, combinedReason);
    }

    private boolean checkRuleSet(EligibilityRuleSet ruleSet,
                                 Applicant applicant,
                                 List<SubjectMark> qualificationMarks,
                                 AcademicRecord qualRecord,
                                 double relaxation,
                                 StringBuilder reason) {

        List<SubjectRequirement> requirements = ruleSet.getSubjectRequirements() != null
                ? ruleSet.getSubjectRequirements()
                : List.of();

        if (requirements.isEmpty()) {
            reason.append("No requirements configured.");
            return false;
        }

        // AND across requirements
        for (SubjectRequirement req : requirements) {
            if (req == null) continue;

            List<String> requiredNames = normalizeSubjects(req.getSubjectNames());
            if (requiredNames.isEmpty()) {
                reason.append("No subjects configured in requirement. ");
                return false;
            }

            boolean ok;
            if (req.getScoreSource() == ScoreSource.NON_CUET) {
                ok = evalNonCuet(req, requiredNames, qualificationMarks, qualRecord, relaxation, reason);
            } else if (req.getScoreSource() == ScoreSource.CUET) {
                ok = evalCuet(req, requiredNames, applicant, relaxation, reason);
            } else {
                reason.append("Invalid score source: ").append(req.getScoreSource()).append(". ");
                return false;
            }

            if (!ok) return false;
        }

        return true;
    }

    private boolean evalNonCuet(SubjectRequirement req,
                                List<String> requiredNames,
                                List<SubjectMark> qualificationMarks,
                                AcademicRecord qualRecord,
                                double relaxation,
                                StringBuilder reason) {

        // ── __OVERALL__: use stored percentage exclusively ────────────────────
        // The stored AcademicRecord.percentage is the authoritative overall result
        // as entered by the applicant. We must NOT fall back to computing it from
        // individual subject marks — applicants only enter the subjects relevant
        // to their programme, so a computed average would be incorrect.
        if (requiredNames.size() == 1
                && "__OVERALL__".equalsIgnoreCase(requiredNames.get(0))) {

            if (qualRecord == null || qualRecord.getPercentage() == null || qualRecord.getPercentage() <= 0) {
                reason.append("Overall percentage not found in academic record. ");
                return false;
            }

            double overallPct = qualRecord.getPercentage();
            double required = Optional.ofNullable(req.getMinScore()).orElse(0.0) - relaxation;
            if (overallPct < required) {
                reason.append(String.format(
                        "Overall %% %.2f < required %.2f (incl. %.1f%% relaxation). ",
                        overallPct, required, relaxation));
                return false;
            }
            return true;
        }
        // ── END __OVERALL__ ──────────────────────────────────────────────────

        Map<String, BigDecimal> byName = new HashMap<>();
        for (SubjectMark sm : qualificationMarks) {
            if (sm == null || sm.getSubject() == null || sm.getSubject().getSubjectName() == null) continue;
            byName.putIfAbsent(sm.getSubject().getSubjectName().trim().toLowerCase(Locale.ROOT),
                    BigDecimal.valueOf(sm.getPercentage()));
        }

        List<String> missing = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (String s : requiredNames) {
            BigDecimal v = byName.get(s.trim().toLowerCase(Locale.ROOT));
            if (v == null) missing.add(s);
            else values.add(v);
        }

        if (!missing.isEmpty()) {
            reason.append("Missing subject(s): ").append(String.join(", ", missing)).append(". ");
            return false;
        }

        BigDecimal min = BigDecimal.valueOf(Optional.ofNullable(req.getMinScore()).orElse(0.0) - relaxation);

        if (req.getCalculationType() == CalculationType.AGGREGATE_AVERAGE) {
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
            if (avg.compareTo(min) < 0) {
                reason.append("NON-CUET average ").append(avg).append(" < required ").append(min).append(". ");
                return false;
            }
            return true;
        }

        if (req.getCalculationType() == CalculationType.INDIVIDUAL_SUBJECT) {
            for (int i = 0; i < requiredNames.size(); i++) {
                String subj = requiredNames.get(i);
                BigDecimal v = values.get(i);
                if (v.compareTo(min) < 0) {
                    reason.append("NON-CUET ").append(subj).append(" ").append(v)
                            .append(" < required ").append(min).append(". ");
                    return false;
                }
            }
            return true;
        }

        reason.append("Invalid calculationType: ").append(req.getCalculationType()).append(". ");
        return false;
    }

    private boolean evalCuet(SubjectRequirement req,
                             List<String> requiredNames,
                             Applicant applicant,
                             double relaxation,
                             StringBuilder reason) {

        CuetScore cuet = applicant.getCuetScore();
        if (cuet == null) {
            reason.append("CUET data missing. ");
            return false;
        }

        List<CuetSubjectScore> subjectScores = cuet.getSubjectScores() != null ? cuet.getSubjectScores() : List.of();

        Map<String, BigDecimal> byCode = new HashMap<>();
        for (CuetSubjectScore css : subjectScores) {
            if (css == null) continue;

            String code = css.getPaperCode();
            if (code == null || code.isBlank()) continue;

            BigDecimal v = css.getScore();
            if (v == null) continue;

            v = v.setScale(2, RoundingMode.HALF_UP);
            byCode.putIfAbsent(code.trim().toUpperCase(Locale.ROOT), v);
        }

        List<String> missing = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (String code : requiredNames) {
            BigDecimal v = byCode.get(code.trim().toUpperCase(Locale.ROOT));
            if (v == null) missing.add(code);
            else values.add(v);
        }

        if (!missing.isEmpty()) {
            reason.append("Missing CUET subject(s): ").append(String.join(", ", missing)).append(". ");
            return false;
        }

        // NULL min_score means the institute only requires the applicant to have
        // appeared for the subject — any score, including negative (due to CUET
        // negative marking), is acceptable. Only apply a score floor when an
        // explicit minimum has been set.
        if (req.getMinScore() == null) {
            return true;
        }

        BigDecimal min = BigDecimal.valueOf(req.getMinScore() - relaxation);

        if (req.getCalculationType() == CalculationType.AGGREGATE_AVERAGE) {
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
            if (avg.compareTo(min) < 0) {
                reason.append("CUET average ").append(avg).append(" < required ").append(min).append(". ");
                return false;
            }
            return true;
        }

        if (req.getCalculationType() == CalculationType.INDIVIDUAL_SUBJECT) {
            for (int i = 0; i < requiredNames.size(); i++) {
                String subj = requiredNames.get(i);
                BigDecimal v = values.get(i);
                if (v.compareTo(min) < 0) {
                    reason.append("CUET ").append(subj).append(" ").append(v)
                            .append(" < required ").append(min).append(". ");
                    return false;
                }
            }
            return true;
        }

        reason.append("Invalid calculationType: ").append(req.getCalculationType()).append(". ");
        return false;
    }

    private List<String> normalizeSubjects(String[] arr) {
        if (arr == null || arr.length == 0) return List.of();
        return Arrays.stream(arr)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private double resolveRelaxation(EligibilityCriteria criteria, Applicant applicant) {
        if (criteria.getCategoryRelaxations() == null) return 0.0;
        if (applicant.getCommunityCategory() == null) return 0.0;

        String applicantCatCode = applicant.getCommunityCategory().getCategoryCode();
        if (applicantCatCode == null || applicantCatCode.isBlank()) return 0.0;

        return criteria.getCategoryRelaxations().stream()
                .filter(r -> r.getCategoryCode() != null
                        && r.getCategoryCode().trim().equalsIgnoreCase(applicantCatCode.trim()))
                .mapToDouble(EligibilityCategoryRelaxation::getRelaxationValue)
                .findFirst()
                .orElse(0.0);
    }

    @Override
    public List<EligibilityResultResponseDTO> getEligibilityForProgramme(String admissionCode, int progId, ApplicantType type) {
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode)
                .orElseThrow(() -> new RuntimeException("Admission window not found for code: " + admissionCode));
        Short windowId = window.getAdmissionId();

        return resultRepo
                .findByProgramme_ProgrammeIdAndApplication_AdmissionWindow_AdmissionIdAndApplication_ApplicantType(
                        progId, windowId, type
                )
                .stream()
                .map(result -> {
                    EligibilityResultResponseDTO dto = new EligibilityResultResponseDTO();
                    dto.setProgrammeName(result.getProgramme().getProgrammeName());
                    dto.setStatus(Boolean.TRUE.equals(result.getIsEligible()) ? "Eligible" : "Not Eligible");
                    dto.setReason(result.getRejectionReason());
                    return dto;
                })
                .toList();
    }

    @Override
    public List<EligibilityListRowDTO> getEligibilityListRowsForProgramme(String admissionCode, int progId, ApplicantType type) {
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode)
                .orElseThrow(() -> new RuntimeException("Admission window not found for code: " + admissionCode));
        Short windowId = window.getAdmissionId();

        return resultRepo.findEligibilityListRows(windowId, progId, type);
    }

    private void saveResult(Application app, Programme programme, boolean eligibleCuet, boolean eligibleNonCuet, String reason) {
        EligibilityResult res = new EligibilityResult();
        res.setApplication(app);
        res.setProgramme(programme);
        res.setIsEligibleCuet(eligibleCuet);
        res.setIsEligibleNonCuet(eligibleNonCuet);
        // Combined display flag, kept for backward compatibility with reports/UI
        // that just want a single yes/no. Round-specific matching queries must
        // use isEligibleCuet / isEligibleNonCuet directly, never this one.
        res.setIsEligible(eligibleCuet || eligibleNonCuet);
        res.setRejectionReason(
                reason != null && reason.length() > 255
                        ? reason.substring(0, 252) + "..."
                        : reason
        );
        resultRepo.save(res);
    }
}