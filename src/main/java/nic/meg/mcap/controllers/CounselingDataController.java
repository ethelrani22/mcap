package nic.meg.mcap.controllers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nic.meg.mcap.dto.request.SeatAllotmentDecisionRequestDTO;
import nic.meg.mcap.dto.request.SubjectPreferenceRequestDTO;
import nic.meg.mcap.dto.response.CounselingRoundResponseDTO;
import nic.meg.mcap.dto.response.SeatAllotmentResponseDTO;
import nic.meg.mcap.dto.response.SubjectPreferenceResponseDTO;
import nic.meg.mcap.services.CounselingService;

@RestController
@RequestMapping("/api/applicants/counseling")
@RequiredArgsConstructor
public class CounselingDataController {

    private static final Logger logger = LoggerFactory.getLogger(CounselingDataController.class);
    private final CounselingService counselingService;

    // =========================================================================
    // 1. SIDEBAR OVERVIEW (Runs on Login/Refresh)
    // =========================================================================
    @GetMapping("/overviews")
    public ResponseEntity<List<CounselingRoundResponseDTO>> getApplicantAllotmentOverviews(
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Collections.emptyList());
        }

        String applicantNo = authentication.getName();
        List<CounselingRoundResponseDTO> overviews = counselingService.getApplicantAllotmentOverviews(applicantNo);
        return ResponseEntity.ok(overviews);
    }

    // =========================================================================
    // 2. FETCH ALLOTMENT (With Auto-Recovery for Blank Screens)
    // =========================================================================
    @GetMapping("/allotment")
    public ResponseEntity<SeatAllotmentResponseDTO> getAllotmentForWindow(
            @RequestParam(value = "admissionWindowId", required = false) Short admissionWindowId,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String applicantNo = authentication.getName();
        SeatAllotmentResponseDTO dto;

        // FIX: If windowId is missing (after logout/login), fetch the most recent
        // active allotment
        if (admissionWindowId == null) {
            dto = counselingService.getLatestSeatAllotment(applicantNo);
        } else {
            dto = counselingService.getSeatAllotmentForWindow(applicantNo, admissionWindowId);
        }

        if (dto == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(dto);
    }

    // =========================================================================
    // 3. DECISION ACTIONS
    // =========================================================================
    // NOTE: Accept and Slide Up are NOT exposed here as direct endpoints.
    // Both require payment (or an existing slide-up fee credit) first, which is
    // resolved entirely by PaymentController (/applicants/payment/initiate-seat-fee
    // → Razorpay → finalizeSuccessfulPayment, which is what actually calls
    // counselingService.acceptAllotment(...) / slideUpAllotment(...)).
    // A direct endpoint here would let anyone bypass payment and get ACCEPTED or
    // SLIDE_UP status for free — do not add one back without a payment check inline.

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> rejectAllotment(
            @Valid @RequestBody SeatAllotmentDecisionRequestDTO requestDTO, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        String applicantNo = authentication.getName();
        try {
            // Reject always releases the seat and keeps the applicant in the pool to be
            // reconsidered next round/phase — there is no separate "hard exit" mode.
            counselingService.rejectAllotment(
                    applicantNo,
                    requestDTO.getAllotmentId(),
                    requestDTO.getReason()
            );

            return ResponseEntity.ok(Map.of(
                    "message", "Offer rejected. You'll be reconsidered for other preferences in the next round."));

        } catch (IllegalStateException e) {
            logger.warn("Reject allotment conflict for applicant {}: {}", applicantNo, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            logger.warn("Reject allotment bad request for applicant {}: {}", applicantNo, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Invalid request"));
        }
    }

    // =========================================================================
    // 4. SUBJECT PREFERENCES
    // =========================================================================
    @PostMapping("/save-combination-preferences")
    public ResponseEntity<Map<String, Object>> saveCombinationPreferences(
            @Valid @RequestBody SubjectPreferenceRequestDTO requestDTO, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        String applicantNo = auth.getName();
        try {
            counselingService.saveCombinationPreferences(applicantNo, requestDTO);

            return ResponseEntity.ok(Map.of("message", "Preferences saved successfully."));

        } catch (IllegalStateException e) {
            logger.warn("Save preferences conflict for applicant {}: {}", applicantNo, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            logger.warn("Save preferences bad request for applicant {}: {}", applicantNo, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Invalid request"));
        }
    }

    @GetMapping("/get-preferences/{allotmentId}")
    public ResponseEntity<SubjectPreferenceResponseDTO> getSavedPreferences(@PathVariable Long allotmentId,
                                                                            Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String applicantNo = auth.getName();
        try {
            return ResponseEntity.ok(counselingService.getSavedPreferences(applicantNo, allotmentId));
        } catch (IllegalArgumentException e) {
            logger.warn("Get preferences not found for applicant {}, allotmentId {}: {}", applicantNo, allotmentId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}