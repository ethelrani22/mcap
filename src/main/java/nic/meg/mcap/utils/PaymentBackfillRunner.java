package nic.meg.mcap.utils;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import nic.meg.mcap.entities.Application;
import nic.meg.mcap.entities.Payment;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.repositories.PaymentRepository;
import nic.meg.mcap.services.ApplicationSubmissionService;
import nic.meg.mcap.services.EligibilityCalculationService;
import nic.meg.mcap.services.PaymentService;

/**
 * One-time backfill runner to:
 *  1. Reconcile Payment records stuck in a non-final state (CREATED /
 *     PAYMENT_ATTEMPTED / PAYMENT_FAILED) against Razorpay's live order
 *     status, correcting our DB if it's out of sync.
 *  2. Enrich existing Payment records with bank/UPI details from Razorpay.
 *  3. Finalize Application records for payments that completed but whose
 *     frontend callback was never received (e.g. applicant closed browser).
 *     This also catches anything Phase 1 just corrected to PAYMENT_SUCCESS.
 *
 * Self-disabling: uses a sentinel Payment record (orderId = BACKFILL_COMPLETE)
 * written to the DB after a successful run. On the next startup, if that
 * sentinel exists, the runner skips immediately — no code change or redeploy needed.
 *
 * HOW TO USE:
 *   Just deploy. It runs once automatically and then disables itself forever.
 *   To force a re-run: DELETE FROM payments WHERE order_id = 'BACKFILL_COMPLETE';
 */
@Component
public class PaymentBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PaymentBackfillRunner.class);

    /** Sentinel orderId written to payments table after a successful run. */
    private static final String SENTINEL_ORDER_ID = "BACKFILL_COMPLETE";

    @Autowired private PaymentRepository             paymentRepository;
    @Autowired private PaymentService                paymentService;
    @Autowired private ApplicationRepository         applicationRepository;
    @Autowired private ApplicationSubmissionService  submissionService;
    @Autowired private EligibilityCalculationService eligibilityCalculationService;

    @Override
    public void run(ApplicationArguments args) {
        // Self-disable check — if sentinel exists, backfill already ran
        if (paymentRepository.findByOrderId(SENTINEL_ORDER_ID).isPresent()) {
            logger.info("PaymentBackfillRunner: sentinel found — already ran, skipping.");
            return;
        }

        logger.info("=== PaymentBackfillRunner START ===");

        runPhase1ReconcileStatusMismatches();
        runPhase2EnrichBankDetails();
        runPhase3FinalizeApplications();

        // Write sentinel so this never runs again on the next restart
        writeSentinel();

        logger.info("=== PaymentBackfillRunner END — sentinel written, will not run again ===");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 1 — Reconcile status mismatches with Razorpay
    //           Targets: payments stuck in CREATED / PAYMENT_ATTEMPTED / PAYMENT_FAILED
    //           that may have actually succeeded (or genuinely failed) on Razorpay's
    //           side without our DB/webhook ever catching up.
    //           Reuses PaymentService.fetchPaymentStatus(), which calls
    //           GET /v1/orders/{razorpayOrderId} and — if it finds a matching local
    //           Payment row — persists the corrected status itself.
    // ─────────────────────────────────────────────────────────────────────────
    private void runPhase1ReconcileStatusMismatches() {
        List<String> nonFinalStatuses = List.of("CREATED", "PAYMENT_ATTEMPTED", "PAYMENT_FAILED");
        List<Payment> toCheck = paymentRepository
                .findByStatusInAndRazorpayOrderIdIsNotNull(nonFinalStatuses);

        logger.info("Phase 1: {} payment(s) in a non-final state — checking against Razorpay", toCheck.size());

        int mismatched = 0, matched = 0, failed = 0;
        for (Payment payment : toCheck) {
            try {
                String localStatus = payment.getStatus();

                // fetchPaymentStatus() hits Razorpay's order endpoint and, internally,
                // saves the corrected status onto the matching Payment row if one is found.
                Map<String, Object> razorpayOrder = paymentService.fetchPaymentStatus(payment.getRazorpayOrderId());
                String rzpStatus = razorpayOrder != null ? (String) razorpayOrder.get("status") : null;

                if (rzpStatus != null) {
                    String mappedStatus = switch (rzpStatus) {
                        case "paid"      -> "PAYMENT_SUCCESS";
                        case "attempted" -> "PAYMENT_ATTEMPTED";
                        default          -> rzpStatus.toUpperCase();
                    };

                    if (!mappedStatus.equals(localStatus)) {
                        mismatched++;
                        logger.warn("Phase 1: MISMATCH order={} local={} razorpay={} — corrected in DB",
                                payment.getOrderId(), localStatus, mappedStatus);
                    } else {
                        matched++;
                    }
                } else {
                    logger.warn("Phase 1: no status returned by Razorpay for order={} (razorpayOrderId={})",
                            payment.getOrderId(), payment.getRazorpayOrderId());
                }

                // ~8 requests/sec — stay under Razorpay rate limit (600 req/min)
                Thread.sleep(120);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.warn("Phase 1: interrupted after {} mismatched, {} matched, {} failed", mismatched, matched, failed);
                return;
            } catch (Exception e) {
                failed++;
                logger.warn("Phase 1: failed to check order {} (razorpayOrderId={}) — {}",
                        payment.getOrderId(), payment.getRazorpayOrderId(), e.getMessage());
            }
        }
        logger.info("Phase 1 complete — mismatched(corrected): {}, matched: {}, failed: {}", mismatched, matched, failed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 2 — Enrich Payment records with bank/UPI details
    //           Targets: payments that have a razorpayPaymentId but no bankRrn yet
    // ─────────────────────────────────────────────────────────────────────────
    private void runPhase2EnrichBankDetails() {
        List<Payment> toEnrich = paymentRepository
                .findByBankRrnIsNullAndRazorpayPaymentIdIsNotNull();

        logger.info("Phase 2: {} payment(s) need bank/UPI detail enrichment", toEnrich.size());

        int success = 0, failed = 0;
        for (Payment payment : toEnrich) {
            try {
                Map<String, Object> details = paymentService
                        .fetchPaymentDetails(payment.getRazorpayPaymentId());
                paymentService.enrichPaymentWithBankDetails(payment, details);
                paymentRepository.save(payment);
                success++;
                logger.info("Phase 2: enriched payment {} (order: {})",
                        payment.getRazorpayPaymentId(), payment.getOrderId());

                // ~8 requests/sec — stay under Razorpay rate limit (600 req/min)
                Thread.sleep(120);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.warn("Phase 2: interrupted after {} success, {} failed", success, failed);
                return;
            } catch (Exception e) {
                failed++;
                logger.warn("Phase 2: failed to enrich payment {} — {}",
                        payment.getRazorpayPaymentId(), e.getMessage());
            }
        }
        logger.info("Phase 2 complete — enriched: {}, failed: {}", success, failed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 3 — Finalize Application records for orphaned successful payments
    //           orderId format: APP_{applicationId}_{timestamp}
    //           Only APP_ orders are processed; SEAT_ and SLIDEUP_ are skipped.
    // ─────────────────────────────────────────────────────────────────────────
    private void runPhase3FinalizeApplications() {
        List<Payment> completedPayments = paymentRepository.findByStatus("PAYMENT_SUCCESS");

        logger.info("Phase 3: {} PAYMENT_SUCCESS record(s) to check", completedPayments.size());

        int fixed = 0, skipped = 0, failed = 0;
        for (Payment payment : completedPayments) {
            try {
                String orderId = payment.getOrderId();

                // Only process application fee payments (APP_)
                if (orderId == null || !orderId.startsWith("APP_")) {
                    skipped++;
                    continue;
                }

                // Parse applicationId from APP_{applicationId}_{timestamp}
                String[] parts = orderId.split("_");
                if (parts.length < 2) {
                    logger.warn("Phase 3: unexpected orderId format '{}' — skipping", orderId);
                    skipped++;
                    continue;
                }
                Long applicationId = Long.parseLong(parts[1]);

                Application app = applicationRepository.findById(applicationId).orElse(null);
                if (app == null) {
                    logger.warn("Phase 3: no Application found for id {} (order: {}) — skipping",
                            applicationId, orderId);
                    skipped++;
                    continue;
                }

                // Already finalized — nothing to do
                if ("SUBMITTED".equals(app.getApplicationStatus())) {
                    skipped++;
                    continue;
                }

                logger.info("Phase 3: finalizing application {} (status was '{}') for order {}",
                        applicationId, app.getApplicationStatus(), orderId);

                submissionService.finalizeApplicationSubmission(app);
                eligibilityCalculationService.calculateAndSaveEligibility(app);
                fixed++;

            } catch (NumberFormatException e) {
                failed++;
                logger.warn("Phase 3: could not parse applicationId from order '{}' — skipping",
                        payment.getOrderId());
            } catch (Exception e) {
                failed++;
                logger.error("Phase 3: failed to finalize for order '{}' — {}",
                        payment.getOrderId(), e.getMessage(), e);
            }
        }
        logger.info("Phase 3 complete — fixed: {}, skipped: {}, failed: {}", fixed, skipped, failed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write sentinel record so this runner never fires again on restart
    // ─────────────────────────────────────────────────────────────────────────
    private void writeSentinel() {
        try {
            Payment sentinel = new Payment();
            sentinel.setOrderId(SENTINEL_ORDER_ID);
            sentinel.setStatus("BACKFILL_COMPLETE");
            sentinel.setCreatedAt(java.time.LocalDateTime.now());
            paymentRepository.save(sentinel);
            logger.info("Sentinel record written (orderId='{}')", SENTINEL_ORDER_ID);
        } catch (Exception e) {
            // Non-fatal — worst case it runs again on next restart (idempotent)
            logger.warn("Could not write sentinel record — backfill may re-run on next restart", e);
        }
    }
}