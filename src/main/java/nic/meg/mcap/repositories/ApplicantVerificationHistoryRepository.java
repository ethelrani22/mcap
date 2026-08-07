package nic.meg.mcap.repositories;

import nic.meg.mcap.entities.Applicant;
import nic.meg.mcap.entities.ApplicantVerificationHistory;
import nic.meg.mcap.enums.VerificationActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApplicantVerificationHistoryRepository extends JpaRepository<ApplicantVerificationHistory, Long> {

    // Deliberately NOT scoped to a single institute: any institute reviewing this
    // applicant in a later round is meant to see the full cross-institute history.
    @Query("""
          SELECT h FROM ApplicantVerificationHistory h
          LEFT JOIN FETCH h.institute i
          LEFT JOIN FETCH h.seatAllotment sa
          LEFT JOIN FETCH sa.programmeOffered po
          LEFT JOIN FETCH po.programme p
          WHERE h.applicant = :applicant
          ORDER BY h.performedAt DESC
          """)
    List<ApplicantVerificationHistory> findByApplicantOrderByPerformedAtDesc(@Param("applicant") Applicant applicant);

    @Query("""
          SELECT h FROM ApplicantVerificationHistory h
          LEFT JOIN FETCH h.institute i
          LEFT JOIN FETCH h.seatAllotment sa
          LEFT JOIN FETCH sa.programmeOffered po
          LEFT JOIN FETCH po.programme p
          WHERE h.applicant.applicantId = :applicantId
          ORDER BY h.performedAt DESC
          """)
    List<ApplicantVerificationHistory> findByApplicantIdOrderByPerformedAtDesc(@Param("applicantId") UUID applicantId);

    /**
     * Latest VERIFIED/REJECTED decision per (applicant, institute) for a given
     * admission window, regardless of which seat_allotment row it was originally
     * logged against or which phase/round it happened in. Used to carry verification
     * forward automatically whenever seat_allotment rows get wiped and regenerated
     * by a fresh allotment run (see SeatAllotmentServiceImpl#runSingleAllocation).
     *
     * DISTINCT ON is Postgres-specific: picks the single most-recent row per
     * (applicant_id, institute_id) pair in one query instead of N+1 lookups.
     *
     * CARRIED_OVER rows are explicitly excluded (action_type IN ('VERIFIED','REJECTED'))
     * so that a re-run after a re-run does not treat a carry-over record as the
     * authoritative verification decision. Only original VERIFIED / REJECTED actions
     * written by a human reviewer are considered canonical. Stale CARRIED_OVER rows
     * from the previous run are deleted by deleteByAdmissionWindowIdAndRoundTypeAnd
     * PhaseNoAndActionType before each re-run (see SeatAllotmentServiceImpl step 1).
     */
    @Query(value = """
          SELECT DISTINCT ON (h.applicant_id, h.institute_id)
                 h.applicant_id      AS applicantId,
                 h.institute_id      AS instituteId,
                 h.action_type       AS actionType,
                 h.remarks           AS remarks
          FROM mcap.applicant_verification_history h
          WHERE h.admission_window_id = :admissionWindowId
            AND h.action_type IN ('VERIFIED', 'REJECTED')
          ORDER BY h.applicant_id, h.institute_id, h.performed_at DESC
          """, nativeQuery = true)
    List<LatestVerificationProjection> findLatestVerificationByAdmissionWindow(
            @Param("admissionWindowId") Short admissionWindowId);

    /**
     * Deletes all CARRIED_OVER history rows for a specific round+phase before a
     * re-run so they do not accumulate and pollute subsequent
     * findLatestVerificationByAdmissionWindow lookups.
     *
     * Called by SeatAllotmentServiceImpl#runSingleAllocation immediately after
     * the old SeatAllotment rows for the same round+phase are deleted.
     * Without this cleanup, each re-run leaves behind orphaned CARRIED_OVER rows
     * (seat_allotment_id = NULL after ON DELETE SET NULL) that could be mistakenly
     * returned by other queries browsing the full history table.
     */
    void deleteByAdmissionWindowIdAndRoundTypeAndPhaseNoAndActionType(
            Short admissionWindowId,
            String roundType,
            Integer phaseNo,
            VerificationActionType actionType);

    interface LatestVerificationProjection {
        UUID getApplicantId();
        Short getInstituteId();
        String getActionType();
        String getRemarks();
    }
}
