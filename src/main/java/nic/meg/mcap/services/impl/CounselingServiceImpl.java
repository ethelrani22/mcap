package nic.meg.mcap.services.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import nic.meg.mcap.dto.request.SubjectPreferenceRequestDTO;
import nic.meg.mcap.dto.request.VerificationRequestDTO;
import nic.meg.mcap.dto.response.CounselingRoundResponseDTO;
import nic.meg.mcap.dto.response.InstituteAllotmentDTO;
import nic.meg.mcap.dto.response.PagedResponse;
import nic.meg.mcap.dto.response.SeatAllotmentResponseDTO;
import nic.meg.mcap.dto.response.SubjectPreferenceResponseDTO;
import nic.meg.mcap.dto.response.VerificationHistoryDTO;
import nic.meg.mcap.entities.AdmissionWindow;
import nic.meg.mcap.entities.Applicant;
import nic.meg.mcap.entities.ApplicantSubjectPreference;
import nic.meg.mcap.entities.ApplicantVerificationHistory;
import nic.meg.mcap.entities.Application;
import nic.meg.mcap.entities.EligibilityResult;
import nic.meg.mcap.entities.Institute;
import nic.meg.mcap.entities.SeatAllotment;
import nic.meg.mcap.entities.User;
import nic.meg.mcap.enums.AllotmentStatus;
import nic.meg.mcap.enums.SubjectType;
import nic.meg.mcap.enums.VerificationActionType;
import nic.meg.mcap.repositories.AdmissionWindowRepository;
import nic.meg.mcap.repositories.ApplicantRepository;
import nic.meg.mcap.repositories.ApplicantSubjectPreferenceRepository;
import nic.meg.mcap.repositories.ApplicantVerificationHistoryRepository;
import nic.meg.mcap.repositories.InstituteRepository;
import nic.meg.mcap.repositories.SeatAllotmentRepository;
import nic.meg.mcap.repositories.ScheduleRepository;
import nic.meg.mcap.repositories.SubjectRepository;
import nic.meg.mcap.repositories.UserRepository;
import nic.meg.mcap.repositories.SeatAllotmentReleaseRepository;
import nic.meg.mcap.repositories.PaymentRepository;
import nic.meg.mcap.entities.SeatAllotmentRelease;
import nic.meg.mcap.services.CounselingService;

@Service
@Transactional
public class CounselingServiceImpl implements CounselingService {

    private static final Logger logger = LoggerFactory.getLogger(CounselingServiceImpl.class);

    @Autowired
    private ApplicantRepository applicantRepository;
    @Autowired
    private SeatAllotmentRepository seatAllotmentRepository;
    @Autowired
    private AdmissionWindowRepository admissionWindowRepository;
    @Autowired
    private ApplicantSubjectPreferenceRepository preferenceRepository;
    @Autowired
    private SubjectRepository subjectRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private ApplicantVerificationHistoryRepository verificationHistoryRepository;
    @Autowired
    private InstituteRepository instituteRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SeatAllotmentReleaseRepository releaseRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private nic.meg.mcap.repositories.ApplicationRepository applicationRepository;
    @Autowired
    private nic.meg.mcap.repositories.EligibilityResultRepository eligibilityResultRepository;

    // ─────────────────────────────────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CounselingRoundResponseDTO> getApplicantAllotmentOverviews(String applicantNo) {
        Applicant applicant = applicantRepository.findByApplicantNo(applicantNo)
                .orElseThrow(() -> new EntityNotFoundException("Applicant not found"));

        Set<AdmissionWindow> relevantWindows = applicant.getApplications().stream()
                .map(Application::getAdmissionWindow)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        relevantWindows.forEach(System.out::println);

        List<SeatAllotment> allAllotments = seatAllotmentRepository.findByApplicant(applicant);

        Map<Short, List<SeatAllotment>> byWindow = allAllotments.stream()
                .filter(sa -> sa.getAdmissionWindow() != null)
                .collect(Collectors.groupingBy(sa -> sa.getAdmissionWindow().getAdmissionId()));

        List<CounselingRoundResponseDTO> result = new ArrayList<>();

        for (AdmissionWindow window : relevantWindows) {
            String title = (window.getStream() != null ? window.getStream().getStreamName() : "All Streams")
                    + " (" + window.getSession() + ")";
            Short windowId = window.getAdmissionId();

            List<SeatAllotment> windowAllotments = byWindow.getOrDefault(windowId, Collections.emptyList());

            if (windowAllotments.isEmpty()) {
                result.add(new CounselingRoundResponseDTO((long) windowId, title, "CUET", 1, "NOT_ALLOTTED", null));
                continue;
            }

            Map<String, List<SeatAllotment>> byRoundPhase = windowAllotments.stream()
                    .collect(Collectors.groupingBy(sa ->
                            (sa.getRoundType() == null ? "CUET" : sa.getRoundType()) + "#"
                                    + (sa.getPhaseNo() == null ? 1 : sa.getPhaseNo())));

            for (List<SeatAllotment> group : byRoundPhase.values()) {
                SeatAllotment latest = group.stream()
                        .max(Comparator.comparing(SeatAllotment::getId)).orElse(null);
                String roundType = (latest != null && latest.getRoundType() != null) ? latest.getRoundType() : "CUET";
                Integer phaseNo  = (latest != null && latest.getPhaseNo()  != null) ? latest.getPhaseNo()  : 1;
                String status    = (latest != null && latest.getStatus()   != null) ? latest.getStatus().name() : "NOT_ALLOTTED";
                Long allotmentId = (latest != null) ? latest.getId() : null;
                boolean slideFeePaid = "SLIDE_UP".equals(status)
                        && paymentRepository.existsSuccessfulSlideUpPaymentForApplicant(applicantNo, "PAYMENT_SUCCESS");
                CounselingRoundResponseDTO dto = new CounselingRoundResponseDTO((long) windowId, title, roundType, phaseNo, status, allotmentId);
                dto.setSlideFeePaid(slideFeePaid);
                result.add(dto);
            }
        }

        result.sort(Comparator
                .comparing(CounselingRoundResponseDTO::getStepName,   Comparator.nullsLast(String::compareTo))
                .thenComparing(CounselingRoundResponseDTO::getRoundType, Comparator.nullsLast(String::compareTo))
                .thenComparing(CounselingRoundResponseDTO::getPhaseNo,   Comparator.nullsLast(Integer::compareTo)));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public SeatAllotmentResponseDTO getSeatAllotmentForWindow(String applicantNo, Short admissionWindowId) {
        Applicant applicant = applicantRepository.findByApplicantNo(applicantNo)
                .orElseThrow(() -> new EntityNotFoundException("Applicant not found"));

        List<SeatAllotment> windowAllotments = seatAllotmentRepository
                .findByApplicantAndAdmissionWindowAdmissionIdOrderByIdDesc(applicant, admissionWindowId);

        if (windowAllotments.isEmpty()) {
            AdmissionWindow window = admissionWindowRepository.findById(admissionWindowId)
                    .orElseThrow(() -> new EntityNotFoundException("Admission Window not found"));

            // Distinguish "hasn't been allotted a seat yet, still eligible and in the
            // running for future rounds" from "failed eligibility entirely for every
            // preferred programme, will never be allotted here regardless of round."
            // Previously both cases showed the exact same reassuring "you will be
            // considered next round" message, which is actively misleading for
            // applicants who are permanently ineligible.
            String status = "NOT_ALLOTTED";
            String ineligibilityReason = null;

            var applicationOpt = applicationRepository
                    .findByAdmissionWindow_AdmissionIdAndApplicant_ApplicantNo(admissionWindowId, applicantNo);
            if (applicationOpt.isPresent()) {
                List<EligibilityResult> results = eligibilityResultRepository
                        .findByApplication_ApplicationId(applicationOpt.get().getApplicationId());
                if (!results.isEmpty() && results.stream().noneMatch(EligibilityResult::getIsEligible)) {
                    status = "NOT_ELIGIBLE";
                    ineligibilityReason = results.stream()
                            .map(EligibilityResult::getRejectionReason)
                            .filter(r -> r != null && !r.isBlank())
                            .findFirst()
                            .orElse("You did not meet the eligibility criteria for your selected programme(s).");
                }
            }

            return SeatAllotmentResponseDTO.builder()
                    .status(status)
                    .verificationRemarks(ineligibilityReason)
                    .roundName((window.getStream() != null ? window.getStream().getStreamName() : "All Streams")
                            + " (" + window.getSession() + ")")
                    .admissionWindowId(admissionWindowId).roundType("CUET").phaseNo(1).build();
        }

        // Return null if the round for this allotment has not been released yet —
        // callers (dashboard hasAllotment flag, page controller) treat null as
        // "nothing to show", which keeps the applicant on the default page.
        SeatAllotment latest = windowAllotments.get(0);
        Short wId = latest.getAdmissionWindow() != null
                ? latest.getAdmissionWindow().getAdmissionId() : null;
        if (wId == null || !isResultsReleased(wId, latest.getRoundType(), latest.getPhaseNo())) {
            return null;
        }

        return convertToSeatAllotmentResponseDTO(latest);
    }

    @Override
    @Transactional(readOnly = true)
    public SeatAllotmentResponseDTO getSeatAllotmentDetailsById(String applicantNo, Long allotmentId) {
        SeatAllotment allotment = seatAllotmentRepository.findByIdWithDetails(allotmentId)
                .orElseThrow(() -> new EntityNotFoundException("Allotment record not found"));

        if (!allotment.getApplicant().getApplicantNo().equals(applicantNo)) {
            throw new SecurityException("Unauthorized access to allotment record");
        }

        return convertToSeatAllotmentResponseDTO(allotment);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ACCEPT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Accepts the allotted seat. Status transition: PENDING → ACCEPTED.
     * Fee resolution (including any prior slide-up fee credit) is fully resolved
     * by PaymentController before this is ever called — this method only flips status.
     */
    @Override
    public void acceptAllotment(String applicantNo, Long allotmentId) {
        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new EntityNotFoundException("Allotment record not found"));

        if (!allotment.getApplicant().getApplicantNo().equals(applicantNo))
            throw new SecurityException("Unauthorized");

        if (allotment.getStatus() != AllotmentStatus.PENDING) {
            throw new IllegalStateException("Cannot action allotment in status: " + allotment.getStatus());
        }

        // RELEASE GATE: block action if admin hasn't released results yet
        Short windowId = allotment.getAdmissionWindow().getAdmissionId();
        if (!isResultsReleased(windowId, allotment.getRoundType(), allotment.getPhaseNo())) {
            throw new IllegalStateException(
                    "Results have not been released yet. Please wait for the institute to complete verification.");
        }

        allotment.setStatus(AllotmentStatus.ACCEPTED);
        seatAllotmentRepository.save(allotment);
    }

    // ─────────────────────────────────────────────────────────────────────
    // REJECT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Rejects/releases the allotted seat. Per spec this is a single behavior —
     * the seat is released and the applicant remains in the pool to be reconsidered
     * in the next round/phase based on their remaining preferences.
     * Status transition: PENDING → REJECTED.
     */
    @Override
    public void rejectAllotment(String applicantNo, Long allotmentId, String reason) {
        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new EntityNotFoundException("Allotment record not found"));

        if (!allotment.getApplicant().getApplicantNo().equals(applicantNo))
            throw new SecurityException("Unauthorized");

        if (allotment.getStatus() != AllotmentStatus.PENDING) {
            throw new IllegalStateException("Cannot action allotment in status: " + allotment.getStatus());
        }

        // RELEASE GATE
        Short windowId = allotment.getAdmissionWindow().getAdmissionId();
        if (!isResultsReleased(windowId, allotment.getRoundType(), allotment.getPhaseNo())) {
            throw new IllegalStateException(
                    "Results have not been released yet. Please wait for the institute to complete verification.");
        }

        allotment.setStatus(AllotmentStatus.REJECTED);

        if (reason != null && !reason.isBlank()) {
            allotment.setRejectionReason(reason.trim());
        }

        seatAllotmentRepository.save(allotment);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SLIDE UP  (pay-once across rounds — checked against the Payment table
    // by PaymentController, never a per-row entity flag, since a new
    // seat_allotment row is created every round)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Confirms a Slide Up onto this allotment — applicant holds this seat, but
     * remains eligible to be proposed a higher-preference seat in future rounds.
     *
     * Whether the flat ₹1000 fee needs to be charged first is decided entirely by
     * PaymentController (via PaymentRepository.existsSuccessfulSlideUpPaymentForApplicant)
     * BEFORE this method is called — by the time this runs, payment has either
     * already succeeded or wasn't required because the applicant paid it in an
     * earlier round. This method just commits the status change and releases
     * whatever seat the applicant previously held via Slide Up (they can only
     * ever hold one seat at a time).
     *
     * Status transition: PENDING → SLIDE_UP.
     */
    @Override
    public void slideUpAllotment(String applicantNo, Long allotmentId) {
        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new EntityNotFoundException("Allotment record not found"));

        if (!allotment.getApplicant().getApplicantNo().equals(applicantNo))
            throw new SecurityException("Unauthorized");

        if (allotment.getStatus() != AllotmentStatus.PENDING) {
            throw new IllegalStateException(
                    "Slide Up is only allowed on a PENDING allotment. Current status: " + allotment.getStatus());
        }

        // RELEASE GATE
        Short windowId = allotment.getAdmissionWindow().getAdmissionId();
        if (!isResultsReleased(windowId, allotment.getRoundType(), allotment.getPhaseNo())) {
            throw new IllegalStateException(
                    "Results have not been released yet. Please wait for the institute to complete verification.");
        }

        // Release any seat this applicant is currently holding via SLIDE_UP from an
        // earlier round/phase — they can only ever hold one seat at a time. Sliding
        // up onto this new (better-preference) allotment means giving up the
        // previous hold, not keeping both.
        List<SeatAllotment> previouslyHeld = seatAllotmentRepository
                .findByApplicantApplicantNoAndStatus(applicantNo, AllotmentStatus.SLIDE_UP);
        for (SeatAllotment prevHeld : previouslyHeld) {
            if (!prevHeld.getId().equals(allotmentId)) {
                prevHeld.setStatus(AllotmentStatus.REJECTED);
                prevHeld.setRejectionReason("Released automatically — applicant slid up to a higher-preference seat.");
                seatAllotmentRepository.save(prevHeld);
            }
        }

        allotment.setStatus(AllotmentStatus.SLIDE_UP);
        seatAllotmentRepository.save(allotment);
    }

    // ─────────────────────────────────────────────────────────────────────
    // INSTITUTE VERIFICATION
    // ─────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<InstituteAllotmentDTO> getPendingVerificationAllotmentsForInstitute(Short instituteId) {
        List<SeatAllotment> allotments = seatAllotmentRepository
                .findByInstituteAndStatusWithDetails(instituteId, AllotmentStatus.PENDING_VERIFICATION);

        return allotments.stream().map(sa -> convertToInstituteDto(sa))
                .filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InstituteAllotmentDTO> getApplicantsForProgrammeAndStatus(
            Integer programmeOfferedId, AllotmentStatus status) {
        List<SeatAllotment> allotments = seatAllotmentRepository
                .findByProgrammeOfferedProgrammeOfferedIdAndStatus(programmeOfferedId, status);
        return allotments.stream().map(this::convertToInstituteDto).collect(Collectors.toList());
    }

    @Override
    public void performVerification(Long allotmentId, VerificationRequestDTO request,
                                    Short instituteId, String username) {
        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new EntityNotFoundException("Allotment not found"));

        Institute institute = allotment.getProgrammeOffered().getInstituteDepartment().getInstitute();
        if (!institute.getInstituteId().equals(instituteId)) {
            throw new SecurityException("Forbidden");
        }

        VerificationActionType actionType;

        if (request.getStatus() == AllotmentStatus.INSTITUTE_REJECTED) {
            if (request.getRemarks() == null || request.getRemarks().isBlank()) {
                throw new IllegalArgumentException("Remarks mandatory for rejection");
            }
            allotment.setStatus(AllotmentStatus.INSTITUTE_REJECTED);
            actionType = VerificationActionType.REJECTED;
            // NOTE: applicant is NOT removed from the pool — INSTITUTE_REJECTED still
            // participates in subsequent allotment rounds/phases.
        } else {
            allotment.setStatus(AllotmentStatus.PENDING);
            actionType = VerificationActionType.VERIFIED;

            Short windowId   = allotment.getAdmissionWindow().getAdmissionId();
            String roundType = allotment.getRoundType();
            Integer phaseNo  = allotment.getPhaseNo();

            LocalDateTime deadline = scheduleRepository
                    .findSeatAcceptanceStep(windowId, roundType, phaseNo)
                    .map(nic.meg.mcap.entities.Schedule::getEndDate)
                    .orElseGet(() -> LocalDateTime.now().plusHours(72));

            allotment.setDecisionDeadline(deadline);
        }

        allotment.setVerificationRemarks(request.getRemarks());
        seatAllotmentRepository.save(allotment);

        logVerificationHistory(allotment, institute, actionType, request.getRemarks(), null, username);
    }

    /**
     * Appends a new, immutable row to the verification history.
     * Every verification decision or edit by an institute is its own permanent record.
     */
    private void logVerificationHistory(SeatAllotment allotment, Institute institute,
                                        VerificationActionType actionType, String remarks,
                                        String changedFieldsJson, String username) {
        User performedBy = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + username));

        ApplicantVerificationHistory history = new ApplicantVerificationHistory();
        history.setApplicant(allotment.getApplicant());
        history.setApplication(allotment.getApplication());
        history.setSeatAllotment(allotment);
        history.setInstitute(institute);
        history.setAdmissionWindowId(allotment.getAdmissionWindow().getAdmissionId());
        history.setRoundType(allotment.getRoundType());
        history.setPhaseNo(allotment.getPhaseNo());
        history.setActionType(actionType);
        history.setRemarks(remarks);
        history.setChangedFields(changedFieldsJson);
        history.setPerformedByUserId(performedBy.getUserId());

        verificationHistoryRepository.save(history);
    }

    @Override
    public void logApplicantEdit(Long allotmentId, Short instituteId,
                                 String username, String changedFieldsJson) {
        if (changedFieldsJson == null) {
            return;
        }

        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new EntityNotFoundException("Allotment not found"));

        Institute institute = allotment.getProgrammeOffered().getInstituteDepartment().getInstitute();
        if (!institute.getInstituteId().equals(instituteId)) {
            throw new SecurityException("Forbidden");
        }

        logVerificationHistory(allotment, institute,
                VerificationActionType.DETAILS_EDITED, null, changedFieldsJson, username);
    }

    // ─────────────────────────────────────────────────────────────────────
    // HISTORY & LISTINGS
    // ─────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<VerificationHistoryDTO> getVerificationHistory(UUID applicantId) {
        return verificationHistoryRepository
                .findByApplicantIdOrderByPerformedAtDesc(applicantId).stream()
                .map(h -> VerificationHistoryDTO.builder()
                        .id(h.getId())
                        .instituteName(h.getInstitute().getInstituteName())
                        .programmeName(h.getSeatAllotment() != null && h.getSeatAllotment().getProgrammeOffered() != null
                                ? h.getSeatAllotment().getProgrammeOffered().getProgramme().getProgrammeName() : null)
                        .roundType(h.getRoundType())
                        .phaseNo(h.getPhaseNo())
                        .actionType(h.getActionType())
                        .remarks(h.getRemarks())
                        .changedFields(h.getChangedFields())
                        .performedAt(h.getPerformedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeatAllotmentResponseDTO> getAllotmentsForApplicant(String applicantNo) {
        Applicant applicant = applicantRepository.findByApplicantNo(applicantNo)
                .orElseThrow(() -> new EntityNotFoundException("Applicant not found"));
        return seatAllotmentRepository.findByApplicant(applicant).stream()
                .map(this::convertToSeatAllotmentResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId, List<AllotmentStatus> statuses, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SeatAllotment> resultPage = seatAllotmentRepository
                .findByInstituteIdAndStatusInPaged(instituteId, statuses, pageable);
        List<InstituteAllotmentDTO> data = resultPage.getContent().stream()
                .map(this::convertToInstituteDto).collect(Collectors.toList());
        return new PagedResponse<>(data, resultPage.getNumber(), resultPage.getSize(),
                resultPage.getTotalElements(), resultPage.getTotalPages(), resultPage.isLast());
    }

    @Override
    @Transactional(readOnly = true)
    public SeatAllotmentResponseDTO getLatestSeatAllotment(String applicantNo) {
        List<SeatAllotment> allotments = seatAllotmentRepository
                .findByApplicant_ApplicantNoOrderByIdDesc(applicantNo);
        if (allotments.isEmpty()) {
            return SeatAllotmentResponseDTO.builder().status("NOT_ALLOTTED").build();
        }
        return convertToSeatAllotmentResponseDTO(allotments.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId, List<AllotmentStatus> statuses, Short programmeId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SeatAllotment> resultPage = seatAllotmentRepository
                .findByInstituteIdAndStatusInPaged(instituteId, statuses, programmeId, pageable);
        List<InstituteAllotmentDTO> data = resultPage.getContent().stream()
                .map(this::convertToInstituteDto).collect(Collectors.toList());
        return new PagedResponse<>(data, resultPage.getNumber(), resultPage.getSize(),
                resultPage.getTotalElements(), resultPage.getTotalPages(), resultPage.isLast());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId, List<AllotmentStatus> statuses, Short programmeId,
            nic.meg.mcap.enums.Shift shift, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SeatAllotment> resultPage = seatAllotmentRepository
                .findByInstituteIdAndStatusInPaged(instituteId, statuses, programmeId, shift, pageable);
        List<InstituteAllotmentDTO> data = resultPage.getContent().stream()
                .map(this::convertToInstituteDto).collect(Collectors.toList());
        return new PagedResponse<>(data, resultPage.getNumber(), resultPage.getSize(),
                resultPage.getTotalElements(), resultPage.getTotalPages(), resultPage.isLast());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId, List<AllotmentStatus> statuses, Short programmeId,
            nic.meg.mcap.enums.Shift shift, String roundType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SeatAllotment> resultPage = seatAllotmentRepository
                .findByInstituteIdAndStatusInPaged(instituteId, statuses, programmeId, shift, roundType, pageable);
        List<InstituteAllotmentDTO> data = resultPage.getContent().stream()
                .map(this::convertToInstituteDto).collect(Collectors.toList());
        return new PagedResponse<>(data, resultPage.getNumber(), resultPage.getSize(),
                resultPage.getTotalElements(), resultPage.getTotalPages(), resultPage.isLast());
    }

    @Override
    @Transactional(readOnly = true)
    public long countAllotmentsByStatus(
            Short instituteId, List<AllotmentStatus> statuses, Short programmeId,
            nic.meg.mcap.enums.Shift shift, String roundType) {
        return seatAllotmentRepository.countByInstituteIdAndStatusIn(instituteId, statuses, programmeId, shift, roundType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InstituteAllotmentDTO> getAllotmentsByStatusList(
            Short instituteId, List<AllotmentStatus> statuses) {
        return seatAllotmentRepository
                .findByProgrammeOffered_InstituteDepartment_Institute_InstituteIdAndStatusIn(
                        instituteId, statuses)
                .stream().map(this::convertToInstituteDto).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────
    // SUBJECT PREFERENCES
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void saveCombinationPreferences(String applicantNo, SubjectPreferenceRequestDTO requestDTO) {
        SeatAllotment allotment = seatAllotmentRepository.findById(requestDTO.getSeatAllotmentId())
                .orElseThrow(() -> new EntityNotFoundException("Allotment not found"));

        if (!allotment.getApplicant().getApplicantNo().equals(applicantNo))
            throw new AccessDeniedException("Forbidden");

        if (allotment.getStatus() == AllotmentStatus.ACCEPTED) {
            throw new IllegalStateException(
                    "Your admission fee has already been paid and your seat is confirmed. "
                            + "Subject preferences can no longer be changed.");
        }
        if (allotment.getStatus() != AllotmentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot set subject preferences in status: " + allotment.getStatus());
        }

        allotment.setChosenShift(requestDTO.getChosenShift());
        preferenceRepository.deleteBySeatAllotment(allotment);

        List<ApplicantSubjectPreference> newPreferences = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : requestDTO.getPreferences().entrySet()) {
            SubjectType type = SubjectType.from(entry.getKey());
            List<Integer> ids = entry.getValue();
            if (type != null && ids != null) {
                for (int i = 0; i < ids.size(); i++) {
                    ApplicantSubjectPreference pref = new ApplicantSubjectPreference();
                    pref.setSeatAllotment(allotment);
                    pref.setSubject(subjectRepository.getReferenceById(ids.get(i)));
                    pref.setSubjectType(type);
                    pref.setPreferenceOrder(i + 1);
                    newPreferences.add(pref);
                }
            }
        }
        preferenceRepository.saveAll(newPreferences);
    }

    @Override
    @Transactional(readOnly = true)
    public SubjectPreferenceResponseDTO getSavedPreferences(String applicantNo, Long allotmentId) {
        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new EntityNotFoundException("Not found"));
        if (!allotment.getApplicant().getApplicantNo().equals(applicantNo))
            throw new AccessDeniedException("Forbidden");

        SubjectPreferenceResponseDTO response = new SubjectPreferenceResponseDTO();
        response.setChosenShift(allotment.getChosenShift());
        response.setPreferences(allotment.getSubjectPreferences().stream()
                .sorted(Comparator.comparingInt(ApplicantSubjectPreference::getPreferenceOrder))
                .collect(Collectors.groupingBy(
                        ApplicantSubjectPreference::getSubjectType,
                        Collectors.mapping(p -> p.getSubject().getSubjectId(), Collectors.toList()))));
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the admin has released results for this round+phase,
     * meaning applicants can see their real status and take action.
     */
    private boolean isResultsReleased(Short windowId, String roundType, Integer phaseNo) {
        return releaseRepository
                .findByAdmissionWindowIdAndRoundTypeAndPhaseNo(windowId, roundType, phaseNo)
                .map(SeatAllotmentRelease::isResultsReleased)
                .orElse(false);
    }

    private boolean isPaymentsClosed(Short windowId, String roundType, Integer phaseNo) {
        return releaseRepository
                .findByAdmissionWindowIdAndRoundTypeAndPhaseNo(windowId, roundType, phaseNo)
                .map(SeatAllotmentRelease::isPaymentsClosed)
                .orElse(false);
    }



    private SeatAllotmentResponseDTO convertToSeatAllotmentResponseDTO(SeatAllotment sa) {
        if (sa == null) return null;

        AdmissionWindow window = sa.getAdmissionWindow();
        String roundName = (window != null && window.getStream() != null)
                ? window.getStream().getStreamName() + " (" + window.getSession() + ")"
                : "General Counseling";

        // RELEASE GATE: until admin explicitly adds a row to seat_allotment_release
        // with resultsReleased = true for this round+phase, applicants see NOTHING
        // about their allotment — every status, including ACCEPTED, REJECTED, and
        // SLIDE_UP, is masked to PENDING_VERIFICATION.
        // No exceptions. The release row must exist and be true before any status
        // is revealed or any action (accept/reject/slide-up) is unblocked.
        Short wId = window != null ? window.getAdmissionId() : null;
        boolean released = wId != null && isResultsReleased(wId, sa.getRoundType(), sa.getPhaseNo());
        String displayStatus = released
                ? sa.getStatus().name()
                : AllotmentStatus.PENDING_VERIFICATION.name();

        // Same-seat-next-round restriction: false only when this allotment is the SAME
        // programme/institute/shift as a seat the applicant already held via SLIDE_UP —
        // meaning no better preference came through, so they must Accept or Reject only.
        boolean sameAsPreviousSlideUpChoice = false;
        if (sa.getStatus() == AllotmentStatus.PENDING && sa.getProgrammeOffered() != null) {
            List<SeatAllotment> priorSlideUps = seatAllotmentRepository
                    .findByApplicantApplicantNoAndStatus(sa.getApplicant().getApplicantNo(), AllotmentStatus.SLIDE_UP);
            sameAsPreviousSlideUpChoice = priorSlideUps.stream()
                    .anyMatch(prev -> !prev.getId().equals(sa.getId())
                            && prev.getProgrammeOffered() != null
                            && prev.getProgrammeOffered().getProgrammeOfferedId()
                            .equals(sa.getProgrammeOffered().getProgrammeOfferedId()));
        }

        // Slide fee credit — checked against the Payment table (customerId = applicantNo),
        // NOT a per-row entity flag, since a fresh seat_allotment row is created every
        // round and a flag on it would never survive to the next round.
        boolean hasSlideFeeCredit = paymentRepository.existsSuccessfulSlideUpPaymentForApplicant(
                sa.getApplicant().getApplicantNo(), "PAYMENT_SUCCESS");

        boolean paymentsClosed = wId != null && isPaymentsClosed(wId, sa.getRoundType(), sa.getPhaseNo());

        return SeatAllotmentResponseDTO.builder()
                .allotmentId(sa.getId())
                .status(displayStatus)
                .roundName(roundName)
                .roundType(sa.getRoundType())
                .phaseNo(sa.getPhaseNo())
                .admissionWindowId(window != null ? window.getAdmissionId() : null)
                .allottedProgramme(sa.getProgrammeOffered() != null
                        ? sa.getProgrammeOffered().getProgramme().getProgrammeName() : "N/A")
                .allottedInstitute(sa.getProgrammeOffered() != null
                        ? sa.getProgrammeOffered().getInstituteDepartment().getInstitute().getInstituteName() : "N/A")
                .programmeOfferedId(sa.getProgrammeOffered() != null
                        ? sa.getProgrammeOffered().getProgrammeOfferedId() : null)
                .shiftName(sa.getChosenShift() != null ? sa.getChosenShift().getDisplayName() : "Day")
                .preferenceNumber(0)
                .verificationRemarks(sa.getVerificationRemarks())
                .decisionDeadline(sa.getDecisionDeadline())
                .canSlideUp(!sameAsPreviousSlideUpChoice)
                // Expose slide fee info so frontend knows whether to show deduction note.
                .slideFeePaid(hasSlideFeeCredit)
                .slideFeeAmount(hasSlideFeeCredit ? 1000.0 : 0.0)
                .paymentsClosed(paymentsClosed)
                .build();
    }

    private InstituteAllotmentDTO convertToInstituteDto(SeatAllotment sa) {
        if (sa == null) return null;
        Applicant applicant = sa.getApplicant();
        String fullName = applicant.getFirstName()
                + (applicant.getMiddleName() != null ? " " + applicant.getMiddleName() : "")
                + " " + applicant.getLastName();

        return InstituteAllotmentDTO.builder()
                .allotmentId(sa.getId())
                .applicantId(applicant.getApplicantId())
                .applicantName(fullName.trim())
                .applicationNo(sa.getApplication().getApplicationNo())
                .programmeName(sa.getProgrammeOffered().getProgramme().getProgrammeName())
                .allottedCategory(sa.getReservationUsed())
                .roundAndPhase(sa.getRoundType() + " / Ph " + sa.getPhaseNo())
                .remarks(sa.getVerificationRemarks())
                .build();
    }
}