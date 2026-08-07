package nic.meg.mcap.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder(toBuilder = true)
public class SeatAllotmentResponseDTO {

    private Long allotmentId;

    // legacy (keep for now; can be removed later if unused)
    private Long roundScheduleId;

    private String roundName;
    private Short admissionWindowId;

    // rounds + phases
    private String roundType;   // "CUET" / "NON_CUET"
    private Integer phaseNo;    // 1..N

    private String status;

    private String allottedProgramme;
    private String allottedInstitute;
    private Integer programmeOfferedId;

    private String shiftName;

    private int preferenceNumber;

    private LocalDateTime deadline;

    private boolean isFinalRound;

    private String verificationRemarks;

    private LocalDateTime decisionDeadline;

    // False when this allotment's programme/institute/shift is the SAME one the applicant
    // already held via a prior SLIDE_UP — meaning no better preference came through, so they
    // must Accept (and pay full admission fee) or Reject; Slide Up is no longer offered.
    private boolean canSlideUp;

    // True only once the admission/seat-acceptance fee payment has actually succeeded
    // (Payment.status == PAYMENT_SUCCESS for a SEAT_ order on this allotment) — NOT merely
    // when status == ACCEPTED. Used by the UI to lock subject-preference selection and
    // show the "already paid" message instead of the subject-selection form.
    private boolean feePaid;

    // True if the applicant has already paid the ₹1000 slide-up fee in this counselling cycle.
    // When true, the next slide is free AND the admission fee on ACCEPT is reduced by ₹1000.
    private boolean slideFeePaid;

    // The slide fee amount in INR (₹1000) if slideFeePaid = true, else 0.
    // The frontend uses this to display the deduction note on the acceptance payment screen.
    private double slideFeeAmount;

    // True when the admin has closed payments for this allotment's admission window +
    // round + phase (SeatAllotmentRelease.paymentsClosed). When true, and the applicant
    // has not yet accepted a seat (status still PENDING), the UI disables the Accept /
    // Slide Up payment buttons and shows a "phase closed, wait for next phase" message
    // instead. Has no effect on applicants who already paid (ACCEPTED / SLIDE_UP).
    private boolean paymentsClosed;
}