package nic.meg.mcap.enums;

public enum VerificationActionType {
    VERIFIED,
    REJECTED,
    DETAILS_EDITED,
    /**
     * System-generated: verification decision automatically carried forward
     * from a prior allotment run for the same admission window, because the
     * applicant was already VERIFIED/REJECTED by this institute before a
     * seat_allotment regeneration wiped the old allotment row. Never written
     * by a human action - always references the freshly created seatAllotment.
     */
    CARRIED_OVER
}