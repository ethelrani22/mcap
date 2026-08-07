package nic.meg.mcap.entities;

import jakarta.persistence.*;
import lombok.*;

/**
 * Controls whether applicants can see and act on their seat allotment results
 * for a specific round and phase.
 *
 * When resultsReleased = false (default after allotment runs):
 *   - Applicants see status as PENDING_VERIFICATION regardless of actual DB status
 *   - Accept / Reject / Slide-Up actions are blocked at the API level
 *
 * When resultsReleased = true (admin explicitly releases):
 *   - Applicants see their real status (PENDING, INSTITUTE_REJECTED, etc.)
 *   - Actions are unblocked
 *
 * This allows:
 *   1. Institutes to complete ALL verifications before any applicant can act
 *   2. CUET and NON-CUET rounds to be released independently — e.g. release
 *      CUET results while NON-CUET is still being verified
 */
@Entity
@Table(
        name = "seat_allotment_release",
        schema = "mcap",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_release_window_round_phase",
                columnNames = {"admission_window_id", "round_type", "phase_no"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatAllotmentRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admission_window_id", nullable = false)
    private Short admissionWindowId;

    @Column(name = "round_type", nullable = false, length = 20)
    private String roundType;           // "CUET" or "NON_CUET"

    @Column(name = "phase_no", nullable = false)
    private Integer phaseNo;

    /**
     * When true, applicants can see their real allotment status and take action.
     * Defaults to false — admin must explicitly release after all verifications done.
     */
    @Column(name = "results_released", nullable = false)
    @Builder.Default
    private boolean resultsReleased = false;

    @Column(name = "released_at")
    private java.time.LocalDateTime releasedAt;

    @Column(name = "released_by_user_id")
    private Long releasedByUserId;

    /**
     * When true, applicants can no longer initiate a NEW seat-fee or slide-up-fee
     * payment for an allotment in this round+phase — used to close a phase off
     * once its payment window has ended.
     *
     * This does NOT affect applicants who already paid (ACCEPTED / SLIDE_UP) — they
     * never call the payment-initiation endpoint again, so their dashboard is
     * completely unaffected. It only blocks applicants still sitting at PENDING
     * from starting a fresh payment once the phase is closed.
     *
     * Defaults to false — a phase's payments stay open until explicitly closed.
     */
    @Column(name = "payments_closed", nullable = false)
    @Builder.Default
    private boolean paymentsClosed = false;

    @Column(name = "payments_closed_at")
    private java.time.LocalDateTime paymentsClosedAt;

    @Column(name = "payments_closed_by_user_id")
    private Long paymentsClosedByUserId;
}