package nic.meg.mcap.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nic.meg.mcap.entities.Application;
import nic.meg.mcap.enums.ApplicationPool;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.services.EligibilityCalculationService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Listens for CriteriaSavedEvent and asynchronously re-calculates eligibility
 * for every SUBMITTED (paid) applicant in the affected programme + window.
 *
 * Progress is tracked via RecalcProgressTracker so the UI can poll
 * GET /api/data/eligibility/recalc-status and show a live progress bar.
 *
 * FIX — Gateway Timeout:
 *   The original listener called calculateAndSaveEligibility() inside one
 *   giant @Async method that held a DB connection open for the entire batch
 *   (up to 1,200+ applicants for large programmes like Education/English).
 *   This caused gateway timeout errors in the browser even though the job
 *   was running fine on the server — because the HTTP POST /recalculate
 *   endpoint returned immediately but the subsequent polling requests were
 *   being blocked by the long-running transaction in the background.
 *
 *   The recalculate endpoint already returns 200 immediately (it just
 *   publishes the event). The real fix is:
 *
 *   1. calculateAndSaveEligibility() is already @Transactional — each
 *      application gets its own short transaction (delete + re-insert).
 *      So we must NOT wrap the entire loop in a single outer transaction,
 *      which the old code was effectively doing through Spring's proxy.
 *
 *   2. We process applicants in batches of BATCH_SIZE and pause briefly
 *      between batches (Thread.sleep). This prevents connection pool
 *      starvation, gives the DB breathing room, and keeps the async
 *      thread from hogging all resources while live traffic is active.
 *
 *   3. The @Async annotation means this runs on a separate thread pool
 *      thread, so the HTTP request thread is never blocked. The gateway
 *      timeout was happening because of shared connection pool exhaustion
 *      under the old single-transaction approach — batching fixes that.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriteriaSavedEventListener {

    /**
     * Number of applications to process before pausing.
     * 50 is a safe default: small enough to release DB connections
     * frequently, large enough to keep throughput high.
     */
    private static final int BATCH_SIZE = 50;

    /**
     * Milliseconds to sleep between batches.
     * Gives the connection pool and the DB a short breather,
     * preventing starvation of other concurrent requests.
     */
    private static final long BATCH_PAUSE_MS = 100;

    private final ApplicationRepository applicationRepository;
    private final EligibilityCalculationService eligibilityCalculationService;
    private final RecalcProgressTracker progressTracker;

    @Async
    @EventListener
    public void onCriteriaSaved(CriteriaSavedEvent event) {
        Short windowId    = event.admissionWindowId();
        Short programmeId = event.programmeId();

        log.info("[CriteriaSaved] Re-evaluating eligibility for windowId={}, programmeId={}", windowId, programmeId);

        List<Application> submittedApplications =
                applicationRepository.findByAdmissionWindow_AdmissionIdAndApplicationStatus(windowId, "SUBMITTED");

        List<Application> relevant = submittedApplications.stream()
                .filter(app -> ApplicationPool.REGULAR.equals(app.getApplicantPool()))
                .filter(app -> app.getApplicantProgrammePreferences() != null
                        && app.getApplicantProgrammePreferences().stream()
                        .anyMatch(pref -> pref.getProgrammeOffered() != null
                                && pref.getProgrammeOffered().getProgramme() != null
                                && programmeId.equals(
                                pref.getProgrammeOffered().getProgramme().getProgrammeId())))
                .toList();

        int total = relevant.size();
        log.info("[CriteriaSaved] Found {} relevant REGULAR SUBMITTED applications to re-evaluate", total);

        // Register the job so the frontend's poll immediately gets a total count
        progressTracker.start(windowId, programmeId, total);

        // No applicants yet — mark done straight away so the overlay doesn't hang
        if (total == 0) {
            progressTracker.finish(windowId, programmeId);
            log.info("[CriteriaSaved] No applicants to recalculate — done immediately");
            return;
        }

        // FIX: Process in batches to avoid connection pool exhaustion.
        // Each calculateAndSaveEligibility() call is its own @Transactional
        // unit (opens, commits, closes), so there is no outer transaction here.
        int batchCount = 0;
        for (Application app : relevant) {
            try {
                eligibilityCalculationService.calculateAndSaveEligibility(app);
            } catch (Exception ex) {
                // Log and continue — one failed recalculation must not block the rest
                log.error("[CriteriaSaved] Failed to re-evaluate eligibility for applicationId={}: {}",
                        app.getApplicationId(), ex.getMessage(), ex);
            } finally {
                // Increment even on failure so the progress bar keeps moving
                progressTracker.increment(windowId, programmeId);
            }

            batchCount++;

            // Pause briefly after each batch to release DB connections
            if (batchCount % BATCH_SIZE == 0) {
                log.debug("[CriteriaSaved] Processed {}/{} — pausing {}ms before next batch",
                        batchCount, total, BATCH_PAUSE_MS);
                try {
                    Thread.sleep(BATCH_PAUSE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[CriteriaSaved] Batch sleep interrupted — stopping recalculation early at {}/{}", batchCount, total);
                    break;
                }
            }
        }

        progressTracker.finish(windowId, programmeId);
        log.info("[CriteriaSaved] Eligibility re-evaluation complete for windowId={}, programmeId={} ({} processed)",
                windowId, programmeId, total);
    }
}