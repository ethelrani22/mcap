package nic.meg.mcap.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import nic.meg.mcap.entities.Application;
import nic.meg.mcap.enums.ApplicantType;
import nic.meg.mcap.enums.ApplicationPool;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    @Query("""
			    SELECT a
			    FROM Application a
			    JOIN FETCH a.applicant
			    JOIN FETCH a.admissionWindow aw
			    LEFT JOIN FETCH aw.stream
			    LEFT JOIN FETCH aw.admissionWindowProgrammes awc
			    LEFT JOIN FETCH awc.programme
			    WHERE a.applicationId = :applicationId
			""")
    Optional<Application> findByIdWithDetails(@Param("applicationId") Long applicationId);

    // Find all applications by admission window and status
    List<Application> findByAdmissionWindow_AdmissionIdAndApplicationStatus(Short windowId, String status);

    // Ownership-only lookup: find ANY application for this window belonging to this applicant
    // (no status filter — used to verify ownership before checking payment/submission state)
    Optional<Application> findByAdmissionWindow_AdmissionIdAndApplicant_ApplicantNo(
            Short admissionWindowId, String applicantNo);

    // Find applications for specific programme
    List<Application> findByAdmissionWindow_AdmissionIdAndAdmissionWindow_AdmissionWindowProgrammes_Programme_ProgrammeIdAndApplicationStatus(
            Short windowId, Short programmeId, String status);

    // Count REGULAR-pool submitted applications for a given window + programme.
    // Used by countApplicantsForUG/PG to produce a totalComplete figure that is
    // consistent with the eligible count (which also excludes LATE applicants).
    @Query("""
			    SELECT COUNT(DISTINCT a)
			    FROM Application a
			    JOIN a.admissionWindow aw
			    JOIN aw.admissionWindowProgrammes awc
			    WHERE aw.admissionId = :windowId
			      AND awc.programme.programmeId = :programmeId
			      AND a.applicationStatus = 'SUBMITTED'
			      AND a.applicantPool = 'REGULAR'
			""")
    int countRegularSubmittedByWindowAndProgramme(@Param("windowId") Short windowId,
                                                  @Param("programmeId") Short programmeId);

    // Only REGULAR-pool applicants are eligible for regular admission rounds.
    // LATE applicants (submitted after the window's original end date but before
    // the extended end date) are excluded here, which propagates the restriction
    // automatically to merit list generation and seat allotment.
    @Query("""
			    SELECT DISTINCT a
			    FROM Application a
			    JOIN a.admissionWindow aw
			    JOIN EligibilityResult er ON er.application = a
			    JOIN ApplicantProgrammePreference appPref ON appPref.application = a
			    WHERE aw.admissionId = :windowId
			      AND er.programme.programmeId = :programmeId
			      AND appPref.programmeOffered.programme.programmeId = :programmeId
			      AND appPref.isActive = true
			      AND a.applicationStatus = 'SUBMITTED'
			      AND a.applicantType = :applicantType
			      AND a.applicantPool = 'REGULAR'
			      AND (
			            (:applicantType = nic.meg.mcap.enums.ApplicantType.WITH_ENTRANCE AND er.isEligibleCuet = true)
			         OR (:applicantType = nic.meg.mcap.enums.ApplicantType.WITHOUT_ENTRANCE AND er.isEligibleNonCuet = true)
			      )
			""")
    List<Application> findEligibleByWindowProgrammeAndApplicantType(@Param("windowId") Short windowId,
                                                                    @Param("programmeId") Short programmeId, @Param("applicantType") ApplicantType applicantType);

    // CARRYOVER: CUET (WITH_ENTRANCE) applicants who went through CUET counselling but never
    // ACCEPTED a seat in ANY CUET-round phase for this window+programme. Per admission policy,
    // once all CUET rounds are done (enforced by AdmissionRouteGuard.assertNonCuetAllowed before
    // this is ever called), these applicants must be folded into Non-CUET Phase 1 so they still
    // get a shot at a seat instead of being silently dropped from the process.
    //
    // Uses the exact same eligibility/preference/pool conditions as
    // findEligibleByWindowProgrammeAndApplicantType, but forces applicantType = WITH_ENTRANCE
    // and adds the "never accepted in CUET" exclusion.
    @Query("""
			    SELECT DISTINCT a
			    FROM Application a
			    JOIN a.admissionWindow aw
			    JOIN EligibilityResult er ON er.application = a
			    JOIN ApplicantProgrammePreference appPref ON appPref.application = a
			    WHERE aw.admissionId = :windowId
			      AND er.programme.programmeId = :programmeId
			      AND appPref.programmeOffered.programme.programmeId = :programmeId
			      AND appPref.isActive = true
			      AND a.applicationStatus = 'SUBMITTED'
			      AND er.isEligibleNonCuet = true
			      AND a.applicantType = nic.meg.mcap.enums.ApplicantType.WITH_ENTRANCE
			      AND a.applicantPool = 'REGULAR'
			      AND NOT EXISTS (
			          SELECT 1 FROM SeatAllotment sa
			          WHERE sa.application = a
			            AND sa.admissionWindow.admissionId = :windowId
			            AND sa.roundType = 'CUET'
			            AND sa.status = nic.meg.mcap.enums.AllotmentStatus.ACCEPTED
			      )
			""")
    List<Application> findUnallottedCuetCarryoverCandidates(@Param("windowId") Short windowId,
                                                            @Param("programmeId") Short programmeId);

    Optional<Application> findByApplicationNo(String applicationNo);

    Optional<Application> findByTransactionId(String transactionId);
}