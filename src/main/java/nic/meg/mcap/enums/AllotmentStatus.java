package nic.meg.mcap.enums;

public enum AllotmentStatus {
    PENDING_VERIFICATION,
    INSTITUTE_REJECTED,
    PENDING,
    ACCEPTED,
    /**
     * Applicant rejected/released this seat. Per spec, this always means they are
     * still considered in the next round/phase based on their remaining preferences
     * — this is not a permanent exit from counselling.
     */
    REJECTED,
    /**
     * Slide Up: Applicant has paid the flat ₹1000 slide fee (tracked via the
     * Payment table, not a per-row flag — see PaymentRepository.
     * existsSuccessfulSlideUpPaymentForApplicant) and is holding this seat,
     * but remains eligible for higher-preference seats in future rounds.
     * On final ACCEPT, the ₹1000 slide fee is deducted from the admission fee.
     * If no action is taken within the deadline, the allotment auto-reverts to REJECTED.
     */
    SLIDE_UP,
    /**
     * Applicant never acted (no Accept/Reject/Slide Up) on a PENDING allotment
     * from an earlier phase before the next phase was generated. Their old row is
     * converted from PENDING to UNATTENDED so it no longer counts as an occupied
     * seat, and they are folded back into the next phase's matching pool fresh
     * (same as REJECTED — not a permanent exit from counselling).
     */
    UNATTENDED
}