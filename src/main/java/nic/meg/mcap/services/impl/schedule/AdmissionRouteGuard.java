package nic.meg.mcap.services.impl.schedule;

import lombok.RequiredArgsConstructor;
import nic.meg.mcap.repositories.ScheduleRepository;
import org.springframework.stereotype.Component;

/**
 * Reports (but no longer blocks) controller-configured admission-route
 * sequencing for a given admission window, based on the schedule steps set up
 * under ScheduleStepTemplate / Schedule (admissionRoute = COMBINED | CUET | NON_CUET).
 *
 * Rules:
 *  - COMBINED phase: CUET and Non-CUET merit list generation / seat allotment
 *    are both allowed to run together. No restriction.
 *  - CUET phase: only CUET processing is allowed for that phase (enforced by
 *    the caller's own route resolution — this guard is a no-op here).
 *  - NON_CUET phase: previously blocked entirely until every CUET counselling
 *    schedule step for this window had ended. By admin decision, this is now
 *    advisory only — the controller may run Non-CUET at any point within the
 *    configured schedule; this method just tells the caller whether CUET
 *    rounds are still active so a warning can be surfaced instead of a hard
 *    failure.
 */
@Component
@RequiredArgsConstructor
public class AdmissionRouteGuard {

    private final ScheduleRepository scheduleRepository;

    /**
     * Call this before running Non-CUET merit list generation or seat
     * allotment for a standalone (non-COMBINED) Non-CUET phase.
     *
     * @return a human-readable warning message if CUET rounds for this window
     *         are not yet fully completed, or null if there's nothing to warn
     *         about. Never throws — the caller decides how/whether to surface
     *         the warning and proceeds with the run regardless.
     */
    public String checkNonCuetSequencing(Short admissionWindowId) {
        long incompleteCuetSteps = scheduleRepository.countIncompleteCuetCounsellingSteps(admissionWindowId);
        if (incompleteCuetSteps > 0) {
            return incompleteCuetSteps
                    + " CUET counselling step(s) for this admission window are still ongoing or upcoming. "
                    + "Running Non-CUET now proceeds anyway, per admin override.";
        }
        return null;
    }
}