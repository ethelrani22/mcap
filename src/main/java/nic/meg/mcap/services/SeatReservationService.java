package nic.meg.mcap.services;

import nic.meg.mcap.dto.request.SeatReservationRequestDTO;
import nic.meg.mcap.dto.response.SeatReservationResponseDTO;
import nic.meg.mcap.entities.SeatReservation;

import java.util.List;

public interface SeatReservationService {

    SeatReservationResponseDTO createReservation(SeatReservationRequestDTO requestDTO);

    List<SeatReservationResponseDTO> getReservationsByProgrammeOffered(Integer programmeOfferedId);

    void deleteReservation(Long id, Integer programmeOfferedId);

    Integer getTotalReservedSeats(Integer programmeOfferedId);

    List<SeatReservationResponseDTO> getReservationsByProgrammeAndWindow(Integer programmeOfferedId, Short admissionWindowId);

    SeatReservationResponseDTO updateReservation(Long reservationId, SeatReservationRequestDTO requestDTO);

    /**
     * Creates PwD + Govt. reservation rows for a programme using the default
     * template values configured in application.properties (mcap.reservation.*).
     * Skips any category that already has a reservation row for this
     * programme (won't overwrite existing manual configuration).
     */
    List<SeatReservationResponseDTO> applyDefaultReservationPolicy(
            Integer programmeOfferedId, Short admissionWindowId, nic.meg.mcap.enums.ApplicantType applicantType);


    default List<SeatReservation> getAllReservationsByProgrammeOfferedId(Integer programmeOfferedId) {
        // This will be overridden in the implementation
        throw new UnsupportedOperationException("Method must be implemented");
    }
}