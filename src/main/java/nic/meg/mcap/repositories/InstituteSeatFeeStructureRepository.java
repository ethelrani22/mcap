package nic.meg.mcap.repositories;

import nic.meg.mcap.entities.InstituteSeatFeeStructure;
import nic.meg.mcap.enums.OrgOwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstituteSeatFeeStructureRepository extends JpaRepository<InstituteSeatFeeStructure, Long> {

    // Collections (particulars, scopes) are LAZY + @BatchSize(25) on the entity.
    // No @EntityGraph needed — Hibernate loads each collection in a separate batch query.
    List<InstituteSeatFeeStructure> findByUser_UserIdAndActiveTrue(Integer userId);

    Optional<InstituteSeatFeeStructure> findByFeeStructureIdAndUser_UserId(Long feeStructureId, Integer userId);

    /**
     * Finds the fee structure applicable to a specific programmeOffered,
     * scoped to the institute that owns that programme.
     *
     * Institutes set fee structures under their own user account
     * (fs.user.orgOwnerId = institute.instituteId, orgOwnerType = 'INSTITUTE').
     * Without the institute filter, structures from other institutes that happen
     * to cover the same stream would bleed in, causing every applicant to be
     * charged whichever institute's fee has the lowest feeStructureId — not their
     * own institute's fee.
     *
     * Priority: direct programme-scope match ranked before stream-scope match,
     * so a programme-specific override always wins over a stream-wide default.
     *
     * NOTE: DISTINCT is intentionally removed and JOIN fs.particulars dropped.
     * PostgreSQL requires all ORDER BY expressions to appear in the SELECT list
     * when DISTINCT is used (SQLState 42P10). The JOIN on particulars caused
     * duplicate rows anyway. Deduplication is handled by the caller via
     * .stream().distinct() — which relies on equals/hashCode on feeStructureId.
     * Particulars are still loaded lazily via @BatchSize on the entity.
     */
    @Query("""
        SELECT fs FROM InstituteSeatFeeStructure fs
        JOIN fs.scopes sc
        WHERE fs.active = true
          AND fs.user.orgOwnerId = (
              SELECT po.instituteDepartment.institute.instituteId
              FROM ProgrammeOffered po WHERE po.programmeOfferedId = :programmeOfferedId
          )
          AND fs.user.orgOwnerType = :ownerType
          AND (
            sc.programmeOffered.programmeOfferedId = :programmeOfferedId
            OR sc.stream.streamId = (
                SELECT po2.programme.stream.streamId
                FROM ProgrammeOffered po2 WHERE po2.programmeOfferedId = :programmeOfferedId
            )
          )
        ORDER BY
          CASE WHEN sc.programmeOffered.programmeOfferedId = :programmeOfferedId THEN 0 ELSE 1 END ASC,
          fs.feeStructureId ASC
    """)
    List<InstituteSeatFeeStructure> findApplicableStructures(@Param("programmeOfferedId") Integer programmeOfferedId,
                                                             @Param("ownerType") OrgOwnerType ownerType);
}
