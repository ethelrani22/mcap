package nic.meg.mcap.services.impl.merit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nic.meg.mcap.entities.*;
import nic.meg.mcap.enums.ProgrammeLevel;
import nic.meg.mcap.repositories.AcademicRecordRepository;
import nic.meg.mcap.repositories.CuetScoreRepository;
import nic.meg.mcap.repositories.JeeScoreRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class MeritScoreCalculator {

    private final AcademicRecordRepository academicRecordRepository;
    private final JeeScoreRepository jeeScoreRepository;
    private final CuetScoreRepository cuetScoreRepository;

    public BigDecimal calculateScore(Applicant applicant, MeritRuleSet ruleSet) {
        if (applicant == null || ruleSet == null) {
            return BigDecimal.ZERO;
        }

        String type = (ruleSet.getSourceType() != null) ? ruleSet.getSourceType().trim().toUpperCase() : "";
        String[] requiredSubjects = ruleSet.getMeritSubjects();

        if ("CUET".equals(type)) {
            return calculateCuetScore(applicant, requiredSubjects);
        } else if ("NON_CUET".equals(type) || "NONCUET".equals(type)) {
            return calculateNonCuetScore(applicant, requiredSubjects);
        }

        return BigDecimal.ZERO;
    }

    private BigDecimal calculateCuetScore(Applicant applicant, String[] paperCodes) {
        Optional<CuetScore> cuetOpt = cuetScoreRepository.findByApplicant(applicant);
        if (cuetOpt.isEmpty() || cuetOpt.get().getSubjectScores() == null || cuetOpt.get().getSubjectScores().isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<CuetSubjectScore> allScores = cuetOpt.get().getSubjectScores();

        // CASE 1: Specific subjects are required by the rule
        if (paperCodes != null && paperCodes.length > 0) {
            Set<String> targetCodes = Arrays.stream(paperCodes)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            List<BigDecimal> matchingPercentiles = allScores.stream()
                    .filter(s -> (s.getPaperCode() != null && targetCodes.contains(s.getPaperCode().trim().toLowerCase()))
                            || (s.getSubjectName() != null && targetCodes.contains(s.getSubjectName().trim().toLowerCase())))
                    .map(s -> s.getPercentile() != null ? s.getPercentile() : BigDecimal.ZERO)
                    .collect(Collectors.toList());

            if (!matchingPercentiles.isEmpty()) {
                return matchingPercentiles.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(matchingPercentiles.size()), 4, RoundingMode.HALF_UP);
            }
        }

        // CASE 2: No specific subjects defined or no matches found -> Average of ALL available papers
        // (Replaces the deleted rollNumber field)
        return allScores.stream()
                .map(s -> s.getPercentile() != null ? s.getPercentile() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(allScores.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateNonCuetScore(Applicant applicant, String[] subjectNamesOrIds) {
        List<AcademicRecord> allRecords = academicRecordRepository.findByApplicant(applicant);
        if (allRecords == null || allRecords.isEmpty()) {
            return BigDecimal.ZERO;
        }

        AcademicRecord targetRecord = allRecords.stream()
                .filter(r -> Boolean.TRUE.equals(r.isLatestQualification()))
                .findFirst()
                .orElse(null);

        if (targetRecord == null) {
            targetRecord = allRecords.stream()
                    .filter(r -> {
                        if (r.getQualificationLevel() == null) return false;
                        String ql = r.getQualificationLevel().toLowerCase();
                        return ql.contains("class xii") || ql.contains("12")
                                || ql.contains("ug") || ql.contains("degree");
                    })
                    .findFirst()
                    .orElse(null);
        }

        if (targetRecord == null) {
            return BigDecimal.ZERO;
        }

        // ── __OVERALL__: Class XII overall percentage ────────────────────────
        // BUG 2 FIX: Use the stored percentage the applicant explicitly filled in
        // (AcademicRecord.percentage) as the merit score for the Class XII fallback
        // condition. Do NOT compute from subject marks — applicants only enter marks
        // for subjects relevant to their programme, not their full mark sheet.
        if (subjectNamesOrIds != null
                && subjectNamesOrIds.length == 1
                && "__OVERALL__".equalsIgnoreCase(subjectNamesOrIds[0].trim())) {

            if (targetRecord.getPercentage() != null && targetRecord.getPercentage() > 0) {
                // Authoritative path: use the overall % the applicant entered
                return BigDecimal.valueOf(targetRecord.getPercentage())
                        .setScale(4, RoundingMode.HALF_UP);
            }
            // Fallback: compute from subject marks only when stored percentage is absent
            if (targetRecord.getSubjectMarks() != null && !targetRecord.getSubjectMarks().isEmpty()) {
                double obtained = targetRecord.getSubjectMarks().stream()
                        .mapToDouble(SubjectMark::getMarksObtained).sum();
                double total = targetRecord.getSubjectMarks().stream()
                        .mapToDouble(SubjectMark::getTotalMarks).sum();
                if (total > 0) {
                    return BigDecimal.valueOf((obtained / total) * 100.0)
                            .setScale(4, RoundingMode.HALF_UP);
                }
            }
            return BigDecimal.ZERO;
        }
        // ── END __OVERALL__ ──────────────────────────────────────────────────

        if (subjectNamesOrIds == null || subjectNamesOrIds.length == 0) {
            return BigDecimal.valueOf(targetRecord.getPercentage());
        }

        Map<String, BigDecimal> subjectMap = new HashMap<>();
        if (targetRecord.getSubjectMarks() != null) {
            for (SubjectMark sm : targetRecord.getSubjectMarks()) {
                if (sm.getSubject() != null) {
                    BigDecimal val = BigDecimal.valueOf(sm.getPercentage());
                    if (sm.getSubject().getSubjectId() != null) {
                        subjectMap.put(String.valueOf(sm.getSubject().getSubjectId()), val);
                    }
                    if (sm.getSubject().getSubjectName() != null) {
                        subjectMap.put(sm.getSubject().getSubjectName().trim().toLowerCase(), val);
                    }
                }
            }
        }

        Set<String> targetNames = Arrays.stream(subjectNamesOrIds)
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        if (targetNames.isEmpty()) {
            return BigDecimal.valueOf(targetRecord.getPercentage());
        }

        BigDecimal totalScore = BigDecimal.ZERO;
        int count = 0;
        for (String target : targetNames) {
            if (subjectMap.containsKey(target)) {
                totalScore = totalScore.add(subjectMap.get(target));
                count++;
            }
        }

        return count > 0 ? totalScore.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(targetRecord.getPercentage());
    }

    public boolean hasEntranceScore(Applicant applicant, ProgrammeLevel level) {
        return jeeScoreRepository.findByApplicant(applicant).isPresent()
                || cuetScoreRepository.findByApplicant(applicant).isPresent();
    }

    public BigDecimal normalizeScore(BigDecimal score, BigDecimal maxScore) {
        if (score == null || maxScore == null || maxScore.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return score.divide(maxScore, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public BigDecimal getClass12Percentage(Applicant applicant) {
        List<AcademicRecord> records = academicRecordRepository.findByApplicant(applicant);
        return records.stream()
                .filter(r -> {
                    if (r.getQualificationLevel() == null) return false;
                    String ql = r.getQualificationLevel().toLowerCase();
                    return ql.contains("class xii") || ql.contains("12");
                })
                .findFirst()
                .map(record -> BigDecimal.valueOf(record.getPercentage()))
                .orElse(BigDecimal.ZERO);
    }

    public BigDecimal getUGDegreePercentage(Applicant applicant) {
        List<AcademicRecord> records = academicRecordRepository.findByApplicant(applicant);
        return records.stream()
                .filter(r -> {
                    if (r.getQualificationLevel() == null) return false;
                    String ql = r.getQualificationLevel().toLowerCase();
                    return ql.contains("ug") || ql.contains("degree");
                })
                .findFirst()
                .map(record -> BigDecimal.valueOf(record.getPercentage()))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * True if the applicant's Class XII (or latest qualifying) record has a
     * dateOfPassing in the current calendar year. Used for the
     * "Preference to current-year pass-outs" tie-breaker.
     */
    public boolean isCurrentYearPassout(Applicant applicant) {
        AcademicRecord record = findClass12OrLatestRecord(applicant);
        if (record == null || record.getDateOfPassing() == null) return false;
        return record.getDateOfPassing().getYear() == java.time.Year.now().getValue();
    }

    /**
     * Percentage marks in a specific subject (by subjectId) from the
     * applicant's Class XII (or latest qualifying) record. Returns ZERO if
     * the applicant has no marks recorded for that subject.
     */
    public BigDecimal getSubjectMarkPercentage(Applicant applicant, Integer subjectId) {
        if (subjectId == null) return BigDecimal.ZERO;
        AcademicRecord record = findClass12OrLatestRecord(applicant);
        if (record == null || record.getSubjectMarks() == null) return BigDecimal.ZERO;

        return record.getSubjectMarks().stream()
                .filter(sm -> sm.getSubject() != null && subjectId.equals(sm.getSubject().getSubjectId()))
                .findFirst()
                .map(sm -> BigDecimal.valueOf(sm.getPercentage()))
                .orElse(BigDecimal.ZERO);
    }

    private AcademicRecord findClass12OrLatestRecord(Applicant applicant) {
        List<AcademicRecord> allRecords = academicRecordRepository.findByApplicant(applicant);
        if (allRecords == null || allRecords.isEmpty()) return null;

        AcademicRecord record = allRecords.stream()
                .filter(r -> Boolean.TRUE.equals(r.isLatestQualification()))
                .findFirst()
                .orElse(null);

        if (record == null) {
            record = allRecords.stream()
                    .filter(r -> r.getQualificationLevel() != null
                            && (r.getQualificationLevel().toLowerCase().contains("class xii")
                            || r.getQualificationLevel().toLowerCase().contains("12")))
                    .findFirst()
                    .orElse(null);
        }
        return record;
    }

    /**
     * Replaces the deleted getOverallPercentile() call for Entrance Score display.
     */
    public BigDecimal getEntranceScore(Applicant applicant, ProgrammeLevel level) {
        var jeeScore = jeeScoreRepository.findByApplicant(applicant);
        if (jeeScore.isPresent()) return jeeScore.get().getBestNtaScore();

        var cuetScoreOpt = cuetScoreRepository.findByApplicant(applicant);
        if (cuetScoreOpt.isPresent()) {
            List<CuetSubjectScore> allScores = cuetScoreOpt.get().getSubjectScores();
            if (allScores == null || allScores.isEmpty()) return BigDecimal.ZERO;

            return allScores.stream()
                    .map(s -> s.getPercentile() != null ? s.getPercentile() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(allScores.size()), 4, RoundingMode.HALF_UP);
        }

        return BigDecimal.ZERO;
    }
}