package nic.meg.mcap.controllers;

import lombok.RequiredArgsConstructor;
import nic.meg.mcap.entities.SeatAllotmentRelease;
import nic.meg.mcap.repositories.SeatAllotmentReleaseRepository;
import nic.meg.mcap.repositories.SeatAllotmentRepository;
import nic.meg.mcap.enums.AllotmentStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin-only endpoints for managing seat allotment result visibility.
 *
 * The flow is:
 *   1. Admin runs allotment → all seats at PENDING_VERIFICATION
 *   2. Institutes log in and verify/reject applicants → seats move to PENDING or INSTITUTE_REJECTED
 *   3. Once all verifications are done for a round+phase, admin hits
 *      POST /release to flip resultsReleased = true
 *   4. Applicants now see their real status and can Accept / Reject / Slide-Up
 *
 * CUET and NON_CUET rounds are released independently — you can release CUET
 * results while NON-CUET is still being verified.
 */
@RestController
@RequestMapping("/api/admin/allotment-release")
@RequiredArgsConstructor
public class SeatApprovalDataController {

    private final SeatAllotmentReleaseRepository releaseRepository;
    private final SeatAllotmentRepository seatAllotmentRepository;

    /**
     * Get release status for all round+phase combinations in a window.
     * Also returns verification progress (how many are still PENDING_VERIFICATION)
     * so admin can see at a glance whether it's safe to release.
     *
     * GET /api/admin/allotment-release?windowId=1
     */
    @GetMapping
    public ResponseEntity<?> getReleaseStatus(@RequestParam Short windowId) {

        // Get all distinct round+phase combos that have allotments in this window
        List<Object[]> combos = seatAllotmentRepository.findDistinctRoundPhaseCombos(windowId);

        List<Map<String, Object>> result = combos.stream().map(row -> {
            String roundType = (String) row[0];
            Integer phaseNo  = ((Number) row[1]).intValue();

            SeatAllotmentRelease release = releaseRepository
                    .findByAdmissionWindowIdAndRoundTypeAndPhaseNo(windowId, roundType, phaseNo)
                    .orElse(null);

            boolean released = release != null && release.isResultsReleased();

            // Count how many are still awaiting verification (safe to release only when 0)
            long pendingVerification = seatAllotmentRepository
                    .countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndStatus(
                            windowId, roundType, phaseNo, AllotmentStatus.PENDING_VERIFICATION);

            long totalAllotments = seatAllotmentRepository
                    .countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNo(
                            windowId, roundType, phaseNo);

            return Map.<String, Object>of(
                    "roundType",           roundType,
                    "phaseNo",             phaseNo,
                    "resultsReleased",     released,
                    "releasedAt",          release != null ? release.getReleasedAt() : null,
                    "totalAllotments",     totalAllotments,
                    "pendingVerification", pendingVerification,
                    "safeToRelease",       pendingVerification == 0
            );
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Release results for a specific round+phase — applicants can now see and act.
     *
     * POST /api/admin/allotment-release/release
     *   ?windowId=1&roundType=CUET&phaseNo=1
     */
    @PostMapping("/release")
    public ResponseEntity<?> releaseResults(
            @RequestParam Short windowId,
            @RequestParam String roundType,
            @RequestParam Integer phaseNo,
            Authentication auth) {

        long pendingVerification = seatAllotmentRepository
                .countByAdmissionWindowAdmissionIdAndRoundTypeAndPhaseNoAndStatus(
                        windowId, roundType, phaseNo, AllotmentStatus.PENDING_VERIFICATION);

        // Warn but don't hard-block — admin may intentionally release early
        // (e.g. a single institute is taking too long and others are done)
        String warning = pendingVerification > 0
                ? pendingVerification + " allotment(s) are still PENDING_VERIFICATION. Consider waiting until all institutes have verified."
                : null;

        SeatAllotmentRelease release = releaseRepository
                .findByAdmissionWindowIdAndRoundTypeAndPhaseNo(windowId, roundType, phaseNo)
                .orElseGet(() -> SeatAllotmentRelease.builder()
                        .admissionWindowId(windowId)
                        .roundType(roundType)
                        .phaseNo(phaseNo)
                        .build());

        release.setResultsReleased(true);
        release.setReleasedAt(LocalDateTime.now());
        releaseRepository.save(release);

        Map<String, Object> response = warning != null
                ? Map.of("message", "Results released. Applicants can now see and act on their allotments.",
                "warning", warning,
                "roundType", roundType, "phaseNo", phaseNo)
                : Map.of("message", "Results released. Applicants can now see and act on their allotments.",
                "roundType", roundType, "phaseNo", phaseNo);

        return ResponseEntity.ok(response);
    }

    /**
     * Revoke a release — hides results from applicants again and blocks actions.
     * Useful if a mistake was found after release (e.g. wrong verification by institute).
     *
     * POST /api/admin/allotment-release/revoke
     *   ?windowId=1&roundType=CUET&phaseNo=1
     */
    @PostMapping("/revoke")
    public ResponseEntity<?> revokeRelease(
            @RequestParam Short windowId,
            @RequestParam String roundType,
            @RequestParam Integer phaseNo,
            Authentication auth) {

        SeatAllotmentRelease release = releaseRepository
                .findByAdmissionWindowIdAndRoundTypeAndPhaseNo(windowId, roundType, phaseNo)
                .orElse(null);

        if (release == null || !release.isResultsReleased()) {
            return ResponseEntity.ok(Map.of("message", "Results were not released — nothing to revoke."));
        }

        release.setResultsReleased(false);
        release.setReleasedAt(null);
        releaseRepository.save(release);

        return ResponseEntity.ok(Map.of(
                "message", "Release revoked. Applicants will see PENDING_VERIFICATION again until re-released.",
                "roundType", roundType,
                "phaseNo", phaseNo
        ));
    }
}