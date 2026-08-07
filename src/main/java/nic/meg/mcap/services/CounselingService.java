package nic.meg.mcap.services;

import nic.meg.mcap.dto.request.SubjectPreferenceRequestDTO;
import nic.meg.mcap.dto.request.VerificationRequestDTO;
import nic.meg.mcap.dto.response.*;
import nic.meg.mcap.enums.AllotmentStatus;

import java.util.List;
import java.util.UUID;

public interface CounselingService {
    List<CounselingRoundResponseDTO> getApplicantAllotmentOverviews(String applicantNo);

    SeatAllotmentResponseDTO getSeatAllotmentForWindow(String applicantNo, Short admissionWindowId);

    /**
     * Accepts the allotted seat. Status transition: PENDING → ACCEPTED.
     * Fee resolution/deduction (including any prior slide-up fee credit) is handled
     * entirely by PaymentController before this is called — this method only flips status.
     */
    void acceptAllotment(String applicantNo, Long allotmentId);

    /**
     * Rejects/releases the allotted seat. Per spec this is a single behavior: the seat
     * is released and the applicant remains in the pool to be considered again in the
     * next round/phase based on their remaining preferences. Status: PENDING → REJECTED.
     *
     * @param reason optional free-text reason the applicant gave for rejecting.
     */
    void rejectAllotment(String applicantNo, Long allotmentId, String reason);

    /**
     * Confirms a Slide Up onto this allotment (holds this seat, stays eligible for a
     * higher preference next round). Releases any other seat this applicant is
     * currently holding via a prior Slide Up. Status: PENDING → SLIDE_UP.
     * Whether the flat ₹1000 fee needs to be charged first is decided by
     * PaymentController (via PaymentRepository.existsSuccessfulSlideUpPaymentForApplicant)
     * before this is ever called.
     */
    void slideUpAllotment(String applicantNo, Long allotmentId);

    SeatAllotmentResponseDTO getSeatAllotmentDetailsById(String applicantNo, Long allotmentId);

    List<SeatAllotmentResponseDTO> getAllotmentsForApplicant(String applicantNo);

    void saveCombinationPreferences(String applicantNo, SubjectPreferenceRequestDTO requestDTO);

    SubjectPreferenceResponseDTO getSavedPreferences(String applicantNo, Long allotmentId);

    List<InstituteAllotmentDTO> getPendingVerificationAllotmentsForInstitute(Short instituteId);

    void performVerification(Long allotmentId, VerificationRequestDTO request, Short instituteId, String username);

    /**
     * Full cross-institute verification + edit history for an applicant, newest first.
     * Not scoped to a single institute by design — any institute reviewing this
     * applicant in a later round can see what previous institutes decided/edited.
     */
    List<VerificationHistoryDTO> getVerificationHistory(UUID applicantId);

    /**
     * Logs an institute-initiated edit to applicant details as a new, immutable
     * DETAILS_EDITED history row. Never overwrites a prior row.
     */
    void logApplicantEdit(Long allotmentId, Short instituteId, String username, String changedFieldsJson);

    List<InstituteAllotmentDTO> getAllotmentsByStatusList(Short instituteId, List<AllotmentStatus> statuses);

    List<InstituteAllotmentDTO> getApplicantsForProgrammeAndStatus(Integer programmeOfferedId, AllotmentStatus status);

    PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId,
            List<AllotmentStatus> statuses,
            int page,
            int size
    );

    /**
     * Finds the most recent allotment for an applicant across all windows.
     * Used to recover the state if the user refreshes or logs back in.
     */
    SeatAllotmentResponseDTO getLatestSeatAllotment(String applicantNo);

    PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId,
            List<AllotmentStatus> statuses,
            Short programmeId,
            int page,
            int size
    );

    PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId,
            List<AllotmentStatus> statuses,
            Short programmeId,
            nic.meg.mcap.enums.Shift shift,
            int page,
            int size
    );

    PagedResponse<InstituteAllotmentDTO> getPagedAllotmentsByStatus(
            Short instituteId,
            List<AllotmentStatus> statuses,
            Short programmeId,
            nic.meg.mcap.enums.Shift shift,
            String roundType,
            int page,
            int size
    );

    /**
     * Lightweight count (no row fetching) used to populate the dashboard stat
     * cards for a given status group, honoring the same filters as the paged listing.
     */
    long countAllotmentsByStatus(
            Short instituteId,
            List<AllotmentStatus> statuses,
            Short programmeId,
            nic.meg.mcap.enums.Shift shift,
            String roundType
    );
}