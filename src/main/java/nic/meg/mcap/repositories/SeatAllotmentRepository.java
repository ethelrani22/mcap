package nic.meg.mcap.repositories;

import nic.meg.mcap.entities.AdmissionWindow;
import nic.meg.mcap.entities.Applicant;
import nic.meg.mcap.entities.Programme;
import nic.meg.mcap.entities.SeatAllotment;
import nic.meg.mcap.enums.AllotmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface SeatAllotmentRepository extends JpaRepository<SeatAllotment, Long> {

    void deleteByAdmissionWindowAdmissionId(Short admissionId);

    @Query("""
          SELECT sa FROM SeatAllotment sa
          LEFT JOIN FETCH sa.programmeOffered po
          LEFT JOIN FETCH po.programme p
          LEFT JOIN FETCH po.instituteDepartment id
          LEFT JOIN FETCH id.institute i
          WHERE sa.applicant = :applicant
            AND sa.admissionWindow = :window
          """)
    Optional<SeatAllotment> findByApplicantAndAdmissionWindowWithDetails(@Param("applicant") Applicant applicant,
                                                                         @Param("window") AdmissionWindow window);

    // -------------------------
    // DRILL-DOWN DASHBOARD
    // -------------------------
    int countByProgrammeOfferedProgrammeOfferedId(Integer programmeOfferedId);

    long countByProgrammeOfferedProgrammeOfferedIdAndStatus(Integer programmeOfferedId, AllotmentStatus status);

    List<SeatAllotment> findByProgrammeOfferedProgrammeOfferedIdAndStatus(Integer programmeOfferedId, AllotmentStatus status);

    // -------------------------
    // Rounds + phases
    // -------------------------
    void deleteByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNo(Short admissionId, String roundType, Integer phaseNo);

    List<SeatAllotment> findByApplicantAndAdmissionWindowAdmissionIdOrderByIdDesc(Applicant applicant, Short admissionId);

    List<SeatAllotment> findByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndProgrammeOfferedProgrammeOfferedId(
            Short admissionId, String roundType, Integer phaseNo, Integer programmeOfferedId);

    int countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndProgrammeOfferedProgrammeOfferedId(Short admissionId,
                                                                                                     String roundType, Integer phaseNo, Integer programmeOfferedId);

    long countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndStatus(Short admissionId, String roundType,
                                                                          Integer phaseNo, AllotmentStatus status);

    long countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNo(Short admissionId, String roundType, Integer phaseNo);

    /**
     * Returns distinct (roundType, phaseNo) pairs that have allotments in the
     * given window. Used by the admin release panel to know which rounds exist.
     */
    @Query("SELECT sa.roundType, sa.phaseNo FROM SeatAllotment sa " +
            "WHERE sa.admissionWindow.admissionId = :windowId " +
            "GROUP BY sa.roundType, sa.phaseNo " +
            "ORDER BY sa.roundType, sa.phaseNo")
    List<Object[]> findDistinctRoundPhaseCombos(@Param("windowId") Short windowId);

    boolean existsByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoLessThanAndApplicationApplicationIdAndStatus(
            Short admissionId, String roundType, Integer phaseNo, Long applicationId, AllotmentStatus status);

    // FIX: UNATTENDED applicants (never acted on a PENDING offer) should not be
    // allotted again in any future phase, unlike REJECTED/INSTITUTE_REJECTED which
    // still get another chance. Used alongside ACCEPTED in the merit-list exclusion
    // filters so both terminal outcomes stop an application from re-competing.
    boolean existsByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoLessThanAndApplicationApplicationIdAndStatusIn(
            Short admissionId, String roundType, Integer phaseNo, Long applicationId, java.util.Collection<AllotmentStatus> statuses);

    boolean existsByAdmissionWindowAdmissionIdAndRoundTypeAndApplicationApplicationIdAndStatusIn(
            Short admissionId, String roundType, Long applicationId, java.util.Collection<AllotmentStatus> statuses);

    @Query("""
          SELECT sa FROM SeatAllotment sa
          LEFT JOIN FETCH sa.programmeOffered po
          LEFT JOIN FETCH po.programme p
          LEFT JOIN FETCH po.instituteDepartment id
          LEFT JOIN FETCH id.institute i
          WHERE sa.applicant = :applicant
            AND sa.admissionWindow = :window
            AND sa.roundType = :roundType
            AND sa.phaseNo = :phaseNo
          """)
    Optional<SeatAllotment> findByApplicantAndWindowAndRoundPhaseWithDetails(@Param("applicant") Applicant applicant,
                                                                             @Param("window") AdmissionWindow window, @Param("roundType") String roundType,
                                                                             @Param("phaseNo") Integer phaseNo);

    List<SeatAllotment> findByApplicant(Applicant applicant);

    @Query("""
    	      SELECT sa FROM SeatAllotment sa
    	      LEFT JOIN FETCH sa.programmeOffered po
    	      LEFT JOIN FETCH po.programme p
    	      LEFT JOIN FETCH po.instituteDepartment id
    	      LEFT JOIN FETCH id.institute i
    	      WHERE sa.id = :allotmentId
    	      """)
    Optional<SeatAllotment> findByIdWithDetails(@Param("allotmentId") Long allotmentId);

    @Query("""
          SELECT sa FROM SeatAllotment sa
          JOIN FETCH sa.applicant app
          JOIN FETCH sa.application appl
          JOIN FETCH sa.programmeOffered po
          JOIN FETCH po.instituteDepartment id
          JOIN FETCH id.department dept
          JOIN FETCH po.programme prog
          JOIN FETCH sa.admissionWindow aw
          WHERE id.institute.instituteId = :instituteId
          ORDER BY sa.id DESC
          """)
    List<SeatAllotment> findAllByInstituteId(@Param("instituteId") Integer instituteId);

    @Query("""
          SELECT COUNT(sa) FROM SeatAllotment sa
          WHERE sa.programmeOffered.instituteDepartment.institute.instituteId = :instituteId
          """)
    Long countByInstituteId(@Param("instituteId") Short instituteId);

    @Query("""
          SELECT COUNT(sa) FROM SeatAllotment sa
          WHERE sa.programmeOffered.instituteDepartment.institute.instituteId = :instituteId
            AND sa.status = :status
          """)
    Long countByInstituteIdAndStatus(@Param("instituteId") Short instituteId, @Param("status") AllotmentStatus status);

    boolean existsByAdmissionWindowAdmissionIdAndRoundTypeAndApplicationApplicationIdAndStatus(Short admissionId,
                                                                                               String roundType, Long applicationId, AllotmentStatus status);

    @Query("""
          SELECT sa FROM SeatAllotment sa
          JOIN FETCH sa.applicant applicant
          JOIN FETCH sa.application application
          JOIN FETCH sa.programmeOffered po
          JOIN FETCH po.programme programme
          WHERE po.instituteDepartment.institute.instituteId = :instituteId
          AND sa.status = :status
          ORDER BY programme.programmeName ASC, applicant.firstName ASC
          """)
    List<SeatAllotment> findByInstituteAndStatusWithDetails(@Param("instituteId") Short instituteId,
                                                            @Param("status") AllotmentStatus status);
    //Filter By Institute Department
    @Query("""
    	    SELECT sa FROM SeatAllotment sa
    	    JOIN FETCH sa.applicant applicant
    	    JOIN FETCH sa.application application
    	    JOIN FETCH sa.programmeOffered po
    	    JOIN FETCH po.programme programme
    	    WHERE po.instituteDepartment.institute.instituteId = :instituteId
    	      AND po.instituteDepartment.instituteDepartmentId = :instituteDepartmentId
    	      AND sa.status = :status
    	    ORDER BY programme.programmeName ASC, applicant.firstName ASC
    	    """)
    List<SeatAllotment> findByInstituteDepartmentAndStatusWithDetails(
            @Param("instituteId") Short instituteId,
            @Param("instituteDepartmentId") Integer instituteDepartmentId,
            @Param("status") AllotmentStatus status);

    @Query("SELECT COUNT(s) > 0 FROM SeatAllotment s " +
            "WHERE s.applicant.applicantId = :applicantId " +
            "AND s.admissionWindow.admissionId = :windowId " +
            "AND s.status NOT IN :excludedStatuses")
    boolean hasActiveAllotment(
            @Param("applicantId") Long applicantId,
            @Param("windowId") Long windowId,
            @Param("excludedStatuses") List<AllotmentStatus> excludedStatuses);
    List<SeatAllotment> findByProgrammeOffered_InstituteDepartment_Institute_InstituteIdAndStatusIn(Short instituteId,
                                                                                                    Collection<AllotmentStatus> statuses);

    List<SeatAllotment> findByProgrammeOffered_InstituteDepartment_InstituteDepartmentIdAndStatusIn(
            Integer instituteDepartmentId,
            Collection<AllotmentStatus> statuses);

    @Query("""
    		SELECT DISTINCT p
    		FROM SeatAllotment sa
    		JOIN sa.programmeOffered po
    		JOIN po.programme p
    		WHERE po.instituteDepartment.instituteDepartmentId = :instituteDepartmentId
    		ORDER BY p.programmeName
    		""")
    List<Programme> findDistinctProgrammesByInstituteDepartmentId(
            @Param("instituteDepartmentId") Integer instituteDepartmentId);
    /**
     * Returns a map of programmeOfferedId → count of allotments in the given
     * round that have one of the specified statuses (e.g. PENDING_VERIFICATION,
     * ACCEPTED). Used by the NONCUET allotment engine to deduct seats already
     * occupied by CUET-round allotments so they are not double-allocated.
     */
    @Query("""
          SELECT sa.programmeOffered.programmeOfferedId AS poId, COUNT(sa) AS cnt
          FROM SeatAllotment sa
          WHERE sa.admissionWindow.admissionId = :admissionWindowId
            AND sa.roundType = :roundType
            AND sa.status IN :statuses
          GROUP BY sa.programmeOffered.programmeOfferedId
          """)
    List<Object[]> countOccupiedByWindowAndRoundAndStatusesRaw(
            @Param("admissionWindowId") Short admissionWindowId,
            @Param("roundType") String roundType,
            @Param("statuses") Collection<AllotmentStatus> statuses);

    default Map<Integer, Long> countOccupiedByWindowAndRoundAndStatuses(
            Short admissionWindowId, String roundType, java.util.Collection<AllotmentStatus> statuses) {
        Map<Integer, Long> result = new java.util.HashMap<>();
        for (Object[] row : countOccupiedByWindowAndRoundAndStatusesRaw(admissionWindowId, roundType, statuses)) {
            result.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return result;
    }

    // -------------------------
    // PAGINATION & RECOVERY (NEW/UPDATED)
    // -------------------------

    @Query("""
          SELECT sa FROM SeatAllotment sa
          JOIN FETCH sa.applicant applicant
          JOIN FETCH sa.application application
          JOIN FETCH sa.programmeOffered po
          JOIN FETCH po.programme programme
          WHERE po.instituteDepartment.institute.instituteId = :instituteId
          AND sa.status IN :statuses
          ORDER BY sa.id DESC
          """)
    Page<SeatAllotment> findByInstituteIdAndStatusInPaged(
            @Param("instituteId") Short instituteId,
            @Param("statuses") Collection<AllotmentStatus> statuses,
            Pageable pageable);

    @Query("""
          SELECT sa FROM SeatAllotment sa
          JOIN FETCH sa.admissionWindow aw
          JOIN FETCH sa.programmeOffered po
          JOIN FETCH po.programme p
          JOIN FETCH po.instituteDepartment id
          JOIN FETCH id.institute i
          WHERE sa.applicant.applicantNo = :applicantNo
          ORDER BY sa.id DESC
          """)
    List<SeatAllotment> findByApplicant_ApplicantNoOrderByIdDesc(@Param("applicantNo") String applicantNo);

    // --- UPDATE THIS EXISTING QUERY ---
    @Query("""
          SELECT sa FROM SeatAllotment sa
          JOIN FETCH sa.applicant applicant
          JOIN FETCH sa.application application
          JOIN FETCH sa.programmeOffered po
          JOIN FETCH po.programme programme
          WHERE po.instituteDepartment.institute.instituteId = :instituteId
          AND sa.status IN :statuses
          AND (:programmeId IS NULL OR programme.programmeId = :programmeId)
          ORDER BY sa.id DESC
          """)
    Page<SeatAllotment> findByInstituteIdAndStatusInPaged(
            @Param("instituteId") Short instituteId,
            @Param("statuses") Collection<AllotmentStatus> statuses,
            @Param("programmeId") Short programmeId, // <-- NEW PARAMETER
            Pageable pageable);

    // --- ADDED: SUPPORTS OPTIONAL SHIFT FILTER ALONGSIDE PROGRAMME FILTER ---
    @Query("""
          SELECT sa FROM SeatAllotment sa
          JOIN FETCH sa.applicant applicant
          JOIN FETCH sa.application application
          JOIN FETCH sa.programmeOffered po
          JOIN FETCH po.programme programme
          WHERE po.instituteDepartment.institute.instituteId = :instituteId
          AND sa.status IN :statuses
          AND (:programmeId IS NULL OR programme.programmeId = :programmeId)
          AND (:shift IS NULL OR po.shift = :shift)
          ORDER BY sa.id DESC
          """)
    Page<SeatAllotment> findByInstituteIdAndStatusInPaged(
            @Param("instituteId") Short instituteId,
            @Param("statuses") Collection<AllotmentStatus> statuses,
            @Param("programmeId") Short programmeId,
            @Param("shift") nic.meg.mcap.enums.Shift shift,
            Pageable pageable);

    // --- ADDED: SUPPORTS OPTIONAL ROUND TYPE (CUET / NON_CUET) FILTER ---
    @Query("""
          SELECT sa FROM SeatAllotment sa
          JOIN FETCH sa.applicant applicant
          JOIN FETCH sa.application application
          JOIN FETCH sa.programmeOffered po
          JOIN FETCH po.programme programme
          WHERE po.instituteDepartment.institute.instituteId = :instituteId
          AND sa.status IN :statuses
          AND (:programmeId IS NULL OR programme.programmeId = :programmeId)
          AND (:shift IS NULL OR po.shift = :shift)
          AND (:roundType IS NULL OR sa.roundType = :roundType)
          ORDER BY sa.id DESC
          """)
    Page<SeatAllotment> findByInstituteIdAndStatusInPaged(
            @Param("instituteId") Short instituteId,
            @Param("statuses") Collection<AllotmentStatus> statuses,
            @Param("programmeId") Short programmeId,
            @Param("shift") nic.meg.mcap.enums.Shift shift,
            @Param("roundType") String roundType,
            Pageable pageable);

    // --- ADDED: LIGHTWEIGHT COUNT QUERY FOR DASHBOARD STAT CARDS ---
    @Query("""
          SELECT COUNT(sa) FROM SeatAllotment sa
          JOIN sa.programmeOffered po
          WHERE po.instituteDepartment.institute.instituteId = :instituteId
          AND sa.status IN :statuses
          AND (:programmeId IS NULL OR po.programme.programmeId = :programmeId)
          AND (:shift IS NULL OR po.shift = :shift)
          AND (:roundType IS NULL OR sa.roundType = :roundType)
          """)
    long countByInstituteIdAndStatusIn(
            @Param("instituteId") Short instituteId,
            @Param("statuses") Collection<AllotmentStatus> statuses,
            @Param("programmeId") Short programmeId,
            @Param("shift") nic.meg.mcap.enums.Shift shift,
            @Param("roundType") String roundType);

    // --- ADD THIS NEW METHOD FOR THE DROPDOWN ---
    @Query("""
          SELECT DISTINCT po.programme 
          FROM SeatAllotment sa 
          JOIN sa.programmeOffered po 
          WHERE po.instituteDepartment.institute.instituteId = :instituteId
          """)
    List<nic.meg.mcap.entities.Programme> findDistinctProgrammesByInstituteId(@Param("instituteId") Short instituteId);
    List<SeatAllotment> findByApplicantApplicantNoAndStatus(String applicantNo, AllotmentStatus status);

    // FIX: Applicants who never acted on a PENDING offer before the next phase was
    // generated get that stale row converted to UNATTENDED (instead of it silently
    // continuing to count as an occupied seat forever). They still return to the
    // matching pool fresh next phase — same as REJECTED, just a distinct status so
    // "never responded" is visible separately from "explicitly declined".
    @Modifying
    @Query("""
          UPDATE SeatAllotment sa
          SET sa.status = nic.meg.mcap.enums.AllotmentStatus.UNATTENDED
          WHERE sa.admissionWindow.admissionId = :admissionId
            AND sa.roundType = :roundType
            AND sa.phaseNo < :currentPhase
            AND sa.status = nic.meg.mcap.enums.AllotmentStatus.PENDING
          """)
    int markStalePendingAsUnattended(@Param("admissionId") Short admissionId,
                                     @Param("roundType") String roundType,
                                     @Param("currentPhase") int currentPhase);

    // FIX: (applicationId, programmeOfferedId) pairs the applicant was REJECTED or
    // INSTITUTE_REJECTED on in an earlier phase of this round. Used to stop the
    // matching engine from re-proposing the exact same institute+programme to an
    // applicant who already declined it or was declined by it — a different
    // programme at the same institute, or the same programme at a different
    // institute, is unaffected.
    @Query("""
          SELECT DISTINCT sa.application.applicationId, sa.programmeOffered.programmeOfferedId
          FROM SeatAllotment sa
          WHERE sa.admissionWindow.admissionId = :admissionId
            AND sa.roundType = :roundType
            AND sa.phaseNo < :currentPhase
            AND sa.status IN (nic.meg.mcap.enums.AllotmentStatus.REJECTED, nic.meg.mcap.enums.AllotmentStatus.INSTITUTE_REJECTED)
          """)
    List<Object[]> findRejectedApplicationProgrammeOfferedPairsRaw(@Param("admissionId") Short admissionId,
                                                                   @Param("roundType") String roundType,
                                                                   @Param("currentPhase") int currentPhase);
}