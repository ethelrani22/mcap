package nic.meg.mcap.services.impl;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nic.meg.mcap.dto.response.AllottedCandidateRowDTO;
import nic.meg.mcap.dto.response.ProgrammeAllocationSummaryDTO;
import nic.meg.mcap.dto.response.SeatAllocationSummaryDTO;
import nic.meg.mcap.dto.response.StudentAllotmentResponseDTO;
import nic.meg.mcap.entities.*;
import nic.meg.mcap.enums.AllotmentStatus;
import nic.meg.mcap.enums.InstituteStatus;
import nic.meg.mcap.enums.ReservationType;
import nic.meg.mcap.enums.VerificationActionType;
import nic.meg.mcap.repositories.*;
import nic.meg.mcap.services.SeatAllotmentService;
import nic.meg.mcap.services.impl.schedule.AdmissionRouteGuard;
import nic.meg.mcap.config.ReservationPolicyConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SeatAllotmentServiceImpl implements SeatAllotmentService {

    private final AdmissionWindowRepository admissionWindowRepository;
    private final ProgrammesOfferedRepository programmesOfferedRepository;
    private final SeatMatrixRepository seatMatrixRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final SeatAllotmentRepository seatAllotmentRepository;
    private final MeritListRepository meritListRepository;
    private final MeritListEntryRepository meritListEntryRepository;
    private final ProgrammePreferenceRepository programmePreferenceRepository;
    private final ScheduleRepository scheduleRepository;
    private final InstituteAdmissionPreferenceRepository instituteAdmissionPreferenceRepository;
    private final AdmissionRouteGuard admissionRouteGuard;
    private final CommunityCategoryRepository communityCategoryRepository;
    private final ReservationPolicyConfig reservationPolicyConfig;
    private final ApplicantVerificationHistoryRepository verificationHistoryRepository;

    // --- Inner Classes for Global Deferred Acceptance Engine ---
    private static class QuotaData {
        int initialOpen;
        Map<String, Integer> initialCategories = new HashMap<>();

        int currentOpen;
        Map<String, Integer> currentCategories = new HashMap<>();

        public void reset() {
            this.currentOpen = initialOpen;
            this.currentCategories.clear();
            this.currentCategories.putAll(initialCategories);
        }
    }

    private static class Proposal {
        Long applicationId;
        Application application;
        Applicant applicant;
        Integer meritRank;
        String categoryCode;
        ApplicantProgrammePreference preference;
        String allottedBucket;

        public Proposal(Long applicationId, Application application, Applicant applicant, Integer meritRank, String categoryCode, ApplicantProgrammePreference preference) {
            this.applicationId = applicationId;
            this.application = application;
            this.applicant = applicant;
            this.meritRank = meritRank;
            this.categoryCode = categoryCode;
            this.preference = preference;
        }
    }

    @Transactional
    @Override
    public SeatAllocationSummaryDTO runAllocationForWindow(String admissionCode, String frontendRoundType, Integer phaseNo) {
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode)
                .orElseThrow(() -> new EntityNotFoundException("Admission window not found: " + admissionCode));

        Short admissionId = window.getAdmissionId();
        int phase = normalizePhaseNo(phaseNo);
        String rt = normalizeRoundType(frontendRoundType);

        // COMBINED phase: resolve actual route from Schedule, then fork into two independent runs
        if ("COMBINED".equals(rt)) {
            log.info("COMBINED phase detected for windowCode={}, phaseNo={}. Forking into CUET + NONCUET runs.", admissionCode, phase);
            runSingleAllocation(window, admissionId, "CUET", phase);
            runSingleAllocation(window, admissionId, "NONCUET", phase);
            // Return a merged summary covering both routes for this phase
            return getMergedAllocationSummary(admissionCode, phase);
        }

        // Standalone NON-CUET phase: previously blocked until all CUET counselling
        // steps for this window had ended. Now advisory-only, per admin decision —
        // proceeds regardless, but the warning (if any) is carried through to the
        // returned summary so the UI can surface it.
        String sequencingWarning = null;
        if ("NONCUET".equals(rt)) {
            sequencingWarning = admissionRouteGuard.checkNonCuetSequencing(admissionId);
            if (sequencingWarning != null) {
                log.warn("Proceeding with Non-CUET run despite incomplete CUET rounds (windowId={}): {}", admissionId, sequencingWarning);
            }
        }

        return runSingleAllocation(window, admissionId, rt, phase, sequencingWarning);
    }

    private SeatAllocationSummaryDTO runSingleAllocation(AdmissionWindow window, Short admissionId, String rt, int phase) {
        return runSingleAllocation(window, admissionId, rt, phase, null);
    }

    /**
     * Runs the full Gale-Shapley allocation for one specific roundType (CUET or NONCUET).
     * This is the core engine — never called with "COMBINED".
     */
    private SeatAllocationSummaryDTO runSingleAllocation(AdmissionWindow window, Short admissionId, String rt, int phase, String sequencingWarning) {
        log.info("Starting GLOBAL Allotment for windowId={}, roundType={}, phaseNo={}", admissionId, rt, phase);

        // 1. CLEAR OLD DATA for this specific round and phase
        seatAllotmentRepository.deleteByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNo(admissionId, rt, phase);

        // Purge stale CARRIED_OVER history rows written by the previous run for this
        // same round+phase. Without this, re-runs accumulate orphaned carry-over rows
        // (seat_allotment_id = NULL after ON DELETE SET NULL) that then pollute the
        // findLatestVerificationByAdmissionWindow lookup and cause the allotment to
        // fail or misclassify verified candidates on every subsequent re-run.
        verificationHistoryRepository.deleteByAdmissionWindowIdAndRoundTypeAndPhaseNoAndActionType(
                admissionId, rt, phase, VerificationActionType.CARRIED_OVER);

        // 2. LOAD GLOBAL QUOTAS
        //
        // CUET round  → only institutes that opted IN (wantsCuet = true).
        //               Seats start at full capacity.
        //
        // NONCUET round → ALL institutes participate (both CUET and non-CUET),
        //                 because Phase 1 was purely a verification phase and now
        //                 we are running the actual allotment across all seats.
        //                 However, any seat already occupied by a CUET-round
        //                 allotment that is PENDING_VERIFICATION or ACCEPTED must
        //                 be treated as taken — the NON-CUET engine only fills
        //                 what is genuinely still available.
        List<ProgrammeOffered> allOfferings = findOfferingsForWindow(window);
        Map<Integer, QuotaData> globalQuotaMap = new HashMap<>();

        // For CUET round: still filter to CUET-only institutes.
        // For NONCUET round: all institutes are eligible — no filtering.
        Set<Short> eligibleInstituteIds = null;
        if ("CUET".equals(rt)) {
            eligibleInstituteIds = instituteAdmissionPreferenceRepository
                    .findInstituteIdsByWindowAndCuetPreference(admissionId, true);
        }

        // FIX: convert any still-PENDING rows from an EARLIER phase of this same
        // round type to UNATTENDED before computing quotas/matching for this phase.
        // An applicant who never acted on a prior offer should not indefinitely
        // keep that seat counted as occupied, and should fall back into the
        // matching pool fresh this phase (same as REJECTED/INSTITUTE_REJECTED).
        int unattendedCount = seatAllotmentRepository.markStalePendingAsUnattended(admissionId, rt, phase);
        String unattendedWarning = null;
        if (unattendedCount > 0) {
            log.info("Converted {} stale PENDING allotments (roundType={}, phase<{}) to UNATTENDED", unattendedCount, rt, phase);
            unattendedWarning = "Previous phase is still active: " + unattendedCount
                    + " applicant(s) who had not responded to their PENDING offer were moved to UNATTENDED "
                    + "and removed from the allotment pool for this and all future phases of this round.";
        }

        // Pre-load already-occupied seats per programmeOffered from the CUET round.
        // Only ACCEPTED (applicant has locked in the seat, final) and SLIDE_UP
        // (applicant is holding this seat as a confirmed fallback while still being
        // considered for a better preference) count as taken/consumed. PENDING is
        // NOT occupied — an unconfirmed offer must not block the seat from being
        // reused; if the applicant never acts, it becomes UNATTENDED (see above) and
        // was never counted as occupied in the first place. PENDING_VERIFICATION
        // (institute hasn't acted yet) is likewise still available.
        // For the very first CUET phase run this map will be empty — no prior CUET seats
        // to worry about yet.
        Map<Integer, Long> cuetOccupiedByPoId = seatAllotmentRepository
                .countOccupiedByWindowAndRoundAndStatuses(
                        admissionId,
                        "CUET",
                        List.of(AllotmentStatus.ACCEPTED, AllotmentStatus.SLIDE_UP));

        for (ProgrammeOffered po : allOfferings) {
            Integer poId = po.getProgrammeOfferedId();
            Short ownerInstituteId = po.getInstituteDepartment().getInstitute().getInstituteId();

            // CUET round: skip non-CUET institutes
            if (eligibleInstituteIds != null && !eligibleInstituteIds.contains(ownerInstituteId)) {
                log.debug("Skipping programmeOfferedId={} (instituteId={}) — not eligible for roundType={}", poId, ownerInstituteId, rt);
                continue;
            }

            SeatMatrix sm = seatMatrixRepository.findByProgrammeOfferedProgrammeOfferedId(poId).orElse(null);
            if (sm == null || !"SUBMITTED".equals(sm.getApprovalStatus())) continue;

            List<SeatReservation> res = getEffectiveReservations(poId, admissionId, sm.getTotalSeats());
            QuotaData qd = new QuotaData();
            int reservedTotal = res.stream().mapToInt(SeatReservation::getReservedSeats).sum();

            // Deduct seats already occupied by CUET-round allotments from this offering
            int cuetOccupied = cuetOccupiedByPoId.getOrDefault(poId, 0L).intValue();
            int effectiveTotal = Math.max(0, sm.getTotalSeats() - cuetOccupied);

            qd.initialOpen = Math.max(0, effectiveTotal - reservedTotal);

            for (SeatReservation sr : res) {
                String bucket = (sr.getCommunityCategory() != null) ? sr.getCommunityCategory().getCategoryCode() : sr.getReservationType().name();
                // Each reserved bucket also loses a proportional share of cuetOccupied.
                // Simple approach: deduct from reserved buckets proportionally, floor to 0.
                int bucketSeats = sr.getReservedSeats();
                if (cuetOccupied > 0 && sm.getTotalSeats() > 0) {
                    int bucketDeduction = (int) Math.floor((double) cuetOccupied * bucketSeats / sm.getTotalSeats());
                    bucketSeats = Math.max(0, bucketSeats - bucketDeduction);
                }
                qd.initialCategories.merge(bucket, bucketSeats, Integer::sum);
            }
            qd.reset();
            globalQuotaMap.put(poId, qd);
        }

        // 3. LOAD GLOBAL RANKS from merit lists for this specific roundType
        Map<Long, Map<Short, Integer>> globalRankMap = new HashMap<>();
        Map<Long, Application> applicationMap = new HashMap<>();
        Set<Long> uniqueApplicants = new HashSet<>();

        // MeritListGenerator persists Non-CUET merit lists with round_type = "NON_CUET"
        // (underscore), while this method's internal `rt` uses "NONCUET" (no underscore)
        // for seat_allotment operations. Bridge the two here so the lookup actually
        // finds the published merit lists instead of silently matching zero rows.
        String meritListRt = "NONCUET".equals(rt) ? "NON_CUET" : rt;
        List<MeritList> meritLists = meritListRepository.findAllByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoOrderByProgrammeProgrammeIdAsc(admissionId, meritListRt, phase);
        for (MeritList ml : meritLists) {
            Short progId = ml.getProgramme().getProgrammeId();
            List<MeritListEntry> entries = meritListEntryRepository.findByMeritListOrderByRank(ml.getMeritListId());
            for (MeritListEntry e : entries) {
                Long appId = e.getApplication().getApplicationId();
                globalRankMap.computeIfAbsent(appId, k -> new HashMap<>()).put(progId, e.getRank());
                applicationMap.put(appId, e.getApplication());
                uniqueApplicants.add(appId);
            }
        }

        // FIX: applicants who were REJECTED (declined) or INSTITUTE_REJECTED on a
        // specific institute+programme in an earlier phase of this same round must
        // not be re-proposed that exact same programmeOffered again. A different
        // programme at the same institute, or the same programme at a different
        // institute (different programmeOfferedId), is unaffected.
        Set<String> rejectedPairs = new HashSet<>();
        for (Object[] row : seatAllotmentRepository.findRejectedApplicationProgrammeOfferedPairsRaw(admissionId, rt, phase)) {
            Long applicationId = ((Number) row[0]).longValue();
            Integer poId = (Integer) row[1];
            rejectedPairs.add(applicationId + "_" + poId);
        }

        // 4. PREPARE GALE-SHAPLEY QUEUE
        Queue<Long> unassigned = new LinkedList<>(uniqueApplicants);
        Map<Long, Integer> currentPrefIdx = new HashMap<>();
        uniqueApplicants.forEach(id -> currentPrefIdx.put(id, 0));

        Map<Integer, List<Proposal>> currentMatches = new HashMap<>();

        // 5. GLOBAL MATCHING LOOP
        while (!unassigned.isEmpty()) {
            Long appId = unassigned.poll();
            Application app = applicationMap.get(appId);
            List<ApplicantProgrammePreference> prefs = programmePreferenceRepository.findByApplicationApplicationIdOrderByPreferenceOrderAsc(appId);

            int idx = currentPrefIdx.get(appId);
            if (prefs == null || idx >= prefs.size()) continue;

            ApplicantProgrammePreference pref = prefs.get(idx);
            Integer targetPoId = pref.getProgrammeOffered().getProgrammeOfferedId();
            Short targetProgId = pref.getProgrammeOffered().getProgramme().getProgrammeId();

            Integer rank = globalRankMap.getOrDefault(appId, Collections.emptyMap()).get(targetProgId);

            boolean previouslyRejectedThisPo = rejectedPairs.contains(appId + "_" + targetPoId);

            if (rank == null || !globalQuotaMap.containsKey(targetPoId) || previouslyRejectedThisPo) {
                currentPrefIdx.put(appId, idx + 1);
                unassigned.add(appId);
                continue;
            }

            // .trim() guards against any DB padding (e.g. "ST " stored with trailing space)
            String catCode = (app.getApplicant().getCommunityCategory() != null)
                    ? app.getApplicant().getCommunityCategory().getCategoryCode().trim() : "OPEN";
            // Policy: SC and OBC share a combined 5% bucket ("Other SC/OBC").
            // Normalise SC to OBC so both draw from the same reserved pool.
            if ("SC".equals(catCode)) catCode = "OBC";

            Proposal p = new Proposal(appId, app, app.getApplicant(), rank, catCode, pref);
            currentMatches.computeIfAbsent(targetPoId, k -> new ArrayList<>()).add(p);

            evaluateGlobalProposals(targetPoId, currentMatches.get(targetPoId), globalQuotaMap.get(targetPoId), unassigned, currentPrefIdx);
        }

        // 6. PERSIST ALLOTMENTS — roundType stored as CUET or NONCUET (never COMBINED)
        //
        // Verification carry-over: regenerating allotment for this window deletes
        // the old seat_allotment rows (step 1 above), which used to silently wipe
        // out any institute verification decision that lived only on that row's
        // status/verification_remarks. applicant_verification_history now survives
        // that delete (seat_allotment_id there is nullable/ON DELETE SET NULL), so
        // we look up the latest VERIFIED/REJECTED decision per (applicant, institute)
        // for this window ONCE up front, then re-apply it to each freshly created row
        // instead of leaving it at PENDING_VERIFICATION.
        Map<String, ApplicantVerificationHistoryRepository.LatestVerificationProjection> priorVerifications =
                verificationHistoryRepository.findLatestVerificationByAdmissionWindow(admissionId).stream()
                        .collect(Collectors.toMap(
                                v -> v.getApplicantId() + "|" + v.getInstituteId(),
                                v -> v,
                                (a, b) -> a));

        for (List<Proposal> finalAllotments : currentMatches.values()) {
            for (Proposal p : finalAllotments) {
                SeatAllotment sa = new SeatAllotment();
                sa.setApplication(p.application);
                sa.setApplicant(p.applicant);
                sa.setAdmissionWindow(window);
                ProgrammeOffered po = p.preference.getProgrammeOffered();
                sa.setProgrammeOffered(po);
                sa.setChosenShift(po.getShift());
                sa.setPreferenceNo(p.preference.getPreferenceOrder());
                sa.setStatus(AllotmentStatus.PENDING_VERIFICATION);
                sa.setRoundType(rt);
                sa.setPhaseNo(phase);
                sa.setReservationUsed(p.allottedBucket);

                Institute institute = po.getInstituteDepartment().getInstitute();
                String key = p.applicant.getApplicantId() + "|" + institute.getInstituteId();
                ApplicantVerificationHistoryRepository.LatestVerificationProjection prior = priorVerifications.get(key);

                if (prior != null) {
                    AllotmentStatus carriedStatus = "REJECTED".equals(prior.getActionType())
                            ? AllotmentStatus.INSTITUTE_REJECTED
                            : AllotmentStatus.PENDING;
                    sa.setStatus(carriedStatus);
                    sa.setVerificationRemarks(prior.getRemarks());
                }

                seatAllotmentRepository.save(sa);

                if (prior != null) {
                    ApplicantVerificationHistory carryOver = new ApplicantVerificationHistory();
                    carryOver.setApplicant(sa.getApplicant());
                    carryOver.setApplication(sa.getApplication());
                    carryOver.setSeatAllotment(sa);
                    carryOver.setInstitute(institute);
                    carryOver.setAdmissionWindowId(admissionId);
                    carryOver.setRoundType(rt);
                    carryOver.setPhaseNo(phase);
                    carryOver.setActionType(VerificationActionType.CARRIED_OVER);
                    carryOver.setRemarks(prior.getRemarks());
                    carryOver.setPerformedByUserId(null);
                    verificationHistoryRepository.save(carryOver);
                }
            }
        }

        SeatAllocationSummaryDTO summary = getAllocationSummary(window.getAdmissionCode(), rt, phase);
        StringBuilder combinedWarning = new StringBuilder();
        if (sequencingWarning != null) combinedWarning.append(sequencingWarning);
        if (unattendedWarning != null) {
            if (combinedWarning.length() > 0) combinedWarning.append(" ");
            combinedWarning.append(unattendedWarning);
        }
        if (combinedWarning.length() > 0) {
            summary.setWarningMessage(combinedWarning.toString());
        }
        return summary;
    }

    /**
     * For a COMBINED phase, merges the CUET and NONCUET summaries into one response
     * so the caller gets a single DTO reflecting both runs.
     */
    private SeatAllocationSummaryDTO getMergedAllocationSummary(String admissionCode, int phase) {
        SeatAllocationSummaryDTO cuetSummary    = getAllocationSummary(admissionCode, "CUET",    phase);
        SeatAllocationSummaryDTO nonCuetSummary = getAllocationSummary(admissionCode, "NONCUET", phase);

        SeatAllocationSummaryDTO merged = new SeatAllocationSummaryDTO();
        merged.setAdmissionCode(admissionCode);
        merged.setTotalProgrammes(cuetSummary.getTotalProgrammes());
        merged.setTotalSeats(cuetSummary.getTotalSeats() + nonCuetSummary.getTotalSeats());
        merged.setTotalAllotted(cuetSummary.getTotalAllotted() + nonCuetSummary.getTotalAllotted());
        merged.setTotalUnfilled(cuetSummary.getTotalUnfilled() + nonCuetSummary.getTotalUnfilled());

        List<ProgrammeAllocationSummaryDTO> combined = new ArrayList<>();
        combined.addAll(cuetSummary.getProgrammeSummaries());
        combined.addAll(nonCuetSummary.getProgrammeSummaries());
        merged.setProgrammeSummaries(combined);

        merged.setCanGenerateNextPhase(
                Boolean.TRUE.equals(cuetSummary.isCanGenerateNextPhase()) &&
                        Boolean.TRUE.equals(nonCuetSummary.isCanGenerateNextPhase())
        );

        return merged;
    }

    private void evaluateGlobalProposals(Integer poId, List<Proposal> activeProposals, QuotaData quota, Queue<Long> unassigned, Map<Long, Integer> prefIdxMap) {
        activeProposals.sort(Comparator.comparingInt(p -> p.meritRank));
        quota.reset();

        List<Proposal> accepted = new ArrayList<>();
        List<Proposal> rejected = new ArrayList<>();

        for (Proposal p : activeProposals) {
            if (quota.currentOpen > 0) {
                p.allottedBucket = "OPEN";
                quota.currentOpen--;
                accepted.add(p);
            } else if (quota.currentCategories.getOrDefault(p.categoryCode, 0) > 0) {
                p.allottedBucket = p.categoryCode;
                quota.currentCategories.put(p.categoryCode, quota.currentCategories.get(p.categoryCode) - 1);
                accepted.add(p);
            } else {
                rejected.add(p);
            }
        }

        for (Proposal r : rejected) {
            prefIdxMap.put(r.applicationId, prefIdxMap.get(r.applicationId) + 1);
            unassigned.add(r.applicationId);
        }

        activeProposals.clear();
        activeProposals.addAll(accepted);
    }

    // --- STANDARD FETCH METHODS ---

    @Override
    public List<AllottedCandidateRowDTO> getAllottedCandidates(String admissionCode, String roundType, Integer phaseNo, Integer programmeOfferedId) {
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode)
                .orElseThrow(() -> new EntityNotFoundException("Admission window not found: " + admissionCode));
        Short admissionId = window.getAdmissionId();

        String rt = normalizeRoundType(roundType);
        int phase = normalizePhaseNo(phaseNo);

        List<SeatAllotment> allotments = seatAllotmentRepository.findByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndProgrammeOfferedProgrammeOfferedId(
                admissionId, rt, phase, programmeOfferedId);

        List<AllottedCandidateRowDTO> rows = new ArrayList<>();
        for (SeatAllotment sa : allotments) {
            Application app = sa.getApplication();
            Applicant applicant = (app != null) ? app.getApplicant() : null;
            ProgrammeOffered po = sa.getProgrammeOffered();

            AllottedCandidateRowDTO dto = new AllottedCandidateRowDTO();

            if (app != null) {
                dto.setApplicationId(app.getApplicationId());
                dto.setRegistrationNumber(app.getApplicationNo());
            }

            if (applicant != null) {
                String fullName = applicant.getFirstName()
                        + (applicant.getMiddleName() != null ? " " + applicant.getMiddleName() : "")
                        + " " + applicant.getLastName();
                dto.setApplicantName(fullName);
                dto.setCommunityCategory(applicant.getCommunityCategory() != null ? applicant.getCommunityCategory().getCategoryName() : "GENERAL");
            }

            dto.setReservationUsed(sa.getReservationUsed() != null ? sa.getReservationUsed() : "OPEN");

            if (po != null) {
                dto.setProgrammeName(po.getProgramme().getProgrammeName());
                dto.setInstituteName(po.getInstituteDepartment().getInstitute().getInstituteName());
                dto.setShiftName(po.getShift() != null ? po.getShift().name() : "Day");
            }

            dto.setAllotmentStatus(sa.getStatus() != null ? sa.getStatus().name() : AllotmentStatus.PENDING.name());

            if (app != null && po != null) {
                Short programmeId = (po.getProgramme() != null) ? po.getProgramme().getProgrammeId() : null;
                Short streamId = (po.getProgramme() != null && po.getProgramme().getStream() != null) ? po.getProgramme().getStream().getStreamId() : null;

                if (programmeId != null || streamId != null) {
                    List<MeritListEntry> entries = meritListEntryRepository.findEntryForAllotment(admissionId, rt, phase, app.getApplicationId(), programmeId, streamId);
                    if (entries != null && !entries.isEmpty()) {
                        dto.setRank(entries.get(0).getRank());
                        dto.setMeritScore(entries.get(0).getMeritScore());
                    }
                }
            }
            rows.add(dto);
        }
        return rows;
    }

    @Transactional(readOnly = true)
    @Override
    public SeatAllocationSummaryDTO getAllocationSummary(String admissionCode, String roundType, Integer phaseNo) {
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode)
                .orElseThrow(() -> new EntityNotFoundException("Admission window not found " + admissionCode));
        Short admissionId = window.getAdmissionId();

        String rt = normalizeRoundType(roundType);
        int phase = normalizePhaseNo(phaseNo);

        if ("COMBINED".equals(rt)) {
            return getMergedAllocationSummary(admissionCode, phase);
        }

        List<ProgrammeOffered> programmes = findOfferingsForWindow(window);
        List<ProgrammeAllocationSummaryDTO> programmeSummaries = new ArrayList<>();
        int totalSeats = 0;
        int totalAllotted = 0;

        for (ProgrammeOffered po : programmes) {
            Integer poId = po.getProgrammeOfferedId();
            Optional<SeatMatrix> seatMatrixOpt = seatMatrixRepository.findByProgrammeOfferedProgrammeOfferedId(poId);
            if (seatMatrixOpt.isEmpty()) continue;

            SeatMatrix seatMatrix = seatMatrixOpt.get();
            int programmeTotalSeats = seatMatrix.getTotalSeats();
            List<SeatReservation> reservations = getEffectiveReservations(poId, admissionId, programmeTotalSeats);

            int reservedSeats = reservations.stream().mapToInt(SeatReservation::getReservedSeats).sum();
            int openSeats = Math.max(0, programmeTotalSeats - reservedSeats);
            int allottedForProgramme = seatAllotmentRepository.countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndProgrammeOfferedProgrammeOfferedId(admissionId, rt, phase, poId);

            ProgrammeAllocationSummaryDTO dto = new ProgrammeAllocationSummaryDTO();
            dto.setProgrammeOfferedId(poId);
            dto.setProgrammeName(po.getProgramme().getProgrammeName());
            dto.setInstituteName(po.getInstituteDepartment().getInstitute().getInstituteName());
            dto.setTotalSeats(programmeTotalSeats);
            dto.setReservedSeats(reservedSeats);
            dto.setOpenSeats(openSeats);
            dto.setAllottedCount(allottedForProgramme);
            dto.setUnfilledSeats(Math.max(0, programmeTotalSeats - allottedForProgramme));

            programmeSummaries.add(dto);
            totalSeats += programmeTotalSeats;
            totalAllotted += allottedForProgramme;
        }

        SeatAllocationSummaryDTO result = new SeatAllocationSummaryDTO();
        result.setAdmissionCode(admissionCode);
        result.setTotalProgrammes(programmes.size());
        result.setTotalSeats(totalSeats);
        result.setTotalAllotted(totalAllotted);
        result.setTotalUnfilled(totalSeats - totalAllotted);
        result.setProgrammeSummaries(programmeSummaries);

        long totalPending = seatAllotmentRepository.countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndStatus(admissionId, rt, phase, AllotmentStatus.PENDING)
                + seatAllotmentRepository.countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndStatus(admissionId, rt, phase, AllotmentStatus.PENDING_VERIFICATION);

        if (totalPending == 0) {
            List<Integer> configuredPhases = scheduleRepository.findDistinctPhasesForWindowAndRound(admissionId, rt);
            Optional<Integer> nextPh = configuredPhases.stream().filter(p -> p > phase).min(Integer::compareTo);
            if (nextPh.isPresent()) {
                result.setCanGenerateNextPhase(true);
                result.setNextPhaseNumber(nextPh.get());
            } else if (scheduleRepository.findDistinctRoundsForWindow(admissionId).contains("NON_CUET")) {
                result.setCanStartNonCuet(true);
            }
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StudentAllotmentResponseDTO> getStudentAllotmentsByInstitute(Integer instituteId) {
        return seatAllotmentRepository.findAllByInstituteId(instituteId).stream()
                .map(this::convertToStudentAllotmentResponseDTO).collect(Collectors.toList());
    }

    private StudentAllotmentResponseDTO convertToStudentAllotmentResponseDTO(SeatAllotment sa) {
        StudentAllotmentResponseDTO dto = new StudentAllotmentResponseDTO();
        dto.setAllotmentId(sa.getId());
        dto.setStudentName(sa.getApplicant().getFirstName() + " " + sa.getApplicant().getLastName());
        dto.setStudentEmail(sa.getApplicant().getEmail());
        dto.setStudentPhone(sa.getApplicant().getPhoneNumber());
        dto.setApplicationNumber(sa.getApplication().getApplicationNo());
        dto.setProgrammeName(sa.getProgrammeOffered().getProgramme().getProgrammeName());
        dto.setDepartmentName(sa.getProgrammeOffered().getInstituteDepartment().getDepartment().getDepartmentName());
        dto.setShiftName(sa.getProgrammeOffered().getShift() != null ? sa.getProgrammeOffered().getShift().name() : "Day");
        dto.setAllotmentStatus(sa.getStatus());
        return dto;
    }

    @Override
    public Long countAllotmentsByInstitute(Short instituteId) {
        return seatAllotmentRepository.countByInstituteIdAndStatus(instituteId, AllotmentStatus.ACCEPTED);
    }

    @Override
    public Long countAcceptedAllotmentsByInstitute(Short instituteId) {
        return countAllotmentsByInstitute(instituteId);
    }

    @Override
    public List<ProgrammeAllocationSummaryDTO> getInstituteProgrammeSummary(Short instituteId, String shiftStr) {
        return programmesOfferedRepository.findByInstituteDepartment_Institute_InstituteIdAndShift(instituteId, nic.meg.mcap.enums.Shift.valueOf(shiftStr.toUpperCase()))
                .stream().map(po -> {
                    ProgrammeAllocationSummaryDTO d = new ProgrammeAllocationSummaryDTO();
                    d.setProgrammeOfferedId(po.getProgrammeOfferedId());
                    d.setProgrammeName(po.getProgramme().getProgrammeName());
                    d.setAllottedCount((int) seatAllotmentRepository.countByProgrammeOfferedProgrammeOfferedIdAndStatus(
                            po.getProgrammeOfferedId(), AllotmentStatus.ACCEPTED));
                    d.setShiftName(po.getShift() != null ? po.getShift().name() : "Day");
                    seatMatrixRepository.findByProgrammeOfferedProgrammeOfferedId(po.getProgrammeOfferedId()).ifPresent(m -> d.setTotalSeats(m.getTotalSeats()));
                    return d;
                }).collect(Collectors.toList());
    }

    @Override
    public List<ProgrammeAllocationSummaryDTO> getProgrammeAllocationSummary(String admissionCode, Short programmeId, String roundType, Integer phaseNo) {
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode).orElseThrow();
        return programmesOfferedRepository.findByProgrammeProgrammeId(programmeId, InstituteStatus.ACCEPTED).stream().map(po -> {
            ProgrammeAllocationSummaryDTO d = new ProgrammeAllocationSummaryDTO();
            d.setProgrammeOfferedId(po.getProgrammeOfferedId());
            d.setProgrammeName(po.getProgramme().getProgrammeName());
            d.setInstituteName(po.getInstituteDepartment().getInstitute().getInstituteName());
            d.setShiftName(po.getShift() != null ? po.getShift().name() : "Day");
            seatMatrixRepository.findByProgrammeOfferedProgrammeOfferedId(po.getProgrammeOfferedId()).ifPresent(m -> {
                d.setTotalSeats(m.getTotalSeats() != null ? m.getTotalSeats() : 0);
                int res = getEffectiveReservations(po.getProgrammeOfferedId(), window.getAdmissionId(), m.getTotalSeats() != null ? m.getTotalSeats() : 0)
                        .stream().mapToInt(SeatReservation::getReservedSeats).sum();
                d.setReservedSeats(res);
                d.setOpenSeats(Math.max(0, (m.getTotalSeats() != null ? m.getTotalSeats() : 0) - res));
            });
            if (d.getTotalSeats() == null) {
                d.setTotalSeats(0);
                d.setReservedSeats(0);
                d.setOpenSeats(0);
            }
            int allot = seatAllotmentRepository.countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndProgrammeOfferedProgrammeOfferedId(window.getAdmissionId(), roundType, phaseNo, po.getProgrammeOfferedId());
            d.setAllottedCount(allot);
            d.setUnfilledSeats(Math.max(0, d.getTotalSeats() - allot));
            return d;
        }).collect(Collectors.toList());
    }

    /**
     * Returns the actual configured seat_reservation rows for this programme+window
     * if any exist. If NONE exist, falls back to the Govt. of Meghalaya reservation
     * policy defined in application.properties. Rows are in-memory only — never persisted.
     *
     * Policy (Order No. 166, Tura Govt. College, 2-7-2026 — applies to all institutes):
     *   ST   ("ST")  → 80%  Indigenous ST (Garo/Khasi/Jaintia)
     *   SC/OBC ("OBC" bucket) →  5%  Other SC/OBC combined; SC applicants are
     *                                normalised to "OBC" at point of bucket lookup
     *   GEN  ("GEN")           → 10%  General category
     *   PWD  (no category)     →  5%  Differently Abled (cross-cutting)
     *   Total                 = 100%
     *
     * application.properties must have:
     *   mcap.reservation.govt.unreserved-percentage=10.0   <- GEN bucket
     *   mcap.reservation.govt.sc-other-st-percentage=5.0   <- shared SC/OBC bucket
     *   mcap.reservation.govt.st-major-tribe-percentage=80.0
     *   mcap.reservation.pwd-percentage=5.0
     */
    private List<SeatReservation> getEffectiveReservations(Integer poId, Short admissionId, int totalSeats) {
        List<SeatReservation> actual = seatReservationRepository.findByProgrammeOfferedIdAndAdmissionWindowId(poId, admissionId);
        if (!actual.isEmpty() || totalSeats <= 0) {
            return actual;
        }

        log.debug("No reservation policy configured for programmeOfferedId={} — applying global default template", poId);

        List<SeatReservation> fallback = new ArrayList<>();
        // Category codes must exactly match community_category.category_code in the DB.
        // No trailing spaces — bucket lookup in evaluateGlobalProposals uses .trim() on the
        // applicant side too, but the bucket key itself must be clean.
        fallback.add(buildTransientReservation("ST",  ReservationType.COMMUNITY, totalSeats, reservationPolicyConfig.getGovt().getStMajorTribePercentage()));
        fallback.add(buildTransientReservation("OBC", ReservationType.COMMUNITY, totalSeats, reservationPolicyConfig.getGovt().getScOtherStPercentage()));
        fallback.add(buildTransientReservation("GEN", ReservationType.COMMUNITY, totalSeats, reservationPolicyConfig.getGovt().getUnreservedPercentage()));
        fallback.add(buildTransientReservation(null,  ReservationType.PWD,        totalSeats, reservationPolicyConfig.getPwdPercentage()));
        return fallback;
    }

    private SeatReservation buildTransientReservation(String categoryCode, ReservationType type, int totalSeats, double percentage) {
        SeatReservation sr = new SeatReservation();
        sr.setReservationType(type);
        int seats = (int) Math.floor(totalSeats * (percentage / 100.0));
        sr.setReservedSeats(seats);
        if (categoryCode != null) {
            communityCategoryRepository.findById(categoryCode).ifPresent(sr::setCommunityCategory);
        }
        return sr;
    }

    private String normalizeRoundType(String roundType) {
        String rt = (roundType == null) ? "CUET" : roundType.trim().toUpperCase(Locale.ROOT);
        if ("NON_CUET".equals(rt)) rt = "NONCUET";
        return rt;
    }

    private int normalizePhaseNo(Integer phaseNo) {
        return (phaseNo == null || phaseNo < 1) ? 1 : phaseNo;
    }

    @Override
    public int countAllotments(String admissionCode, String roundType, Integer phaseNo, Integer programmeOfferedId) {
        AdmissionWindow w = admissionWindowRepository.findByAdmissionCode(admissionCode).orElseThrow();
        return seatAllotmentRepository.countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndProgrammeOfferedProgrammeOfferedId(w.getAdmissionId(), normalizeRoundType(roundType), normalizePhaseNo(phaseNo), programmeOfferedId);
    }

    /**
     * Returns all ProgrammeOffered records applicable to an admission window.
     *
     * - Specific-stream window (Arts / Science / Commerce): fetches only that stream.
     * - "All Streams" window (stream == null, e.g. FYUG): fetches by programme level
     *   across every stream that has offerings — no hardcoded stream IDs.
     */
    private List<ProgrammeOffered> findOfferingsForWindow(AdmissionWindow window) {
        if (window.getStream() != null && window.getStream().getStreamId() != null) {
            return programmesOfferedRepository.findByStreamProgramme(
                    List.of(window.getStream().getStreamId()),
                    window.getProgrammeLevel()
            );
        }
        return (List<ProgrammeOffered>) programmesOfferedRepository
                .findByProgramme_ProgrammeLevel(window.getProgrammeLevel());
    }
}