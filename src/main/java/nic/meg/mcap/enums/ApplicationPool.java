package nic.meg.mcap.enums;

public enum     ApplicationPool {
    /**
     * Application submitted on or before 10 July 2026 23:59:59.
     * Eligible for all regular rounds of seat allotment.
     */
    REGULAR,

    /**
     * Application submitted after 10 July 2026 23:59:59.
     * Considered only after completion of all regular rounds,
     * and only if vacant seats are still available.
     */
    LATE
}
