package nic.meg.mcap.repositories;

import nic.meg.mcap.entities.SeatAllotmentRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SeatAllotmentReleaseRepository extends JpaRepository<SeatAllotmentRelease, Long> {

    Optional<SeatAllotmentRelease> findByAdmissionWindowIdAndRoundTypeAndPhaseNo(
            Short admissionWindowId, String roundType, Integer phaseNo);
}