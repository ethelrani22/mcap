package nic.meg.mcap.controllers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import nic.meg.mcap.entities.Application;
import nic.meg.mcap.entities.Payment;
import nic.meg.mcap.entities.SeatAllotment;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.repositories.PaymentRepository;
import nic.meg.mcap.repositories.SeatAllotmentRepository;
import nic.meg.mcap.services.ApplicationService;
import nic.meg.mcap.services.ApplicationSubmissionService;
import nic.meg.mcap.services.CounselingService;
import nic.meg.mcap.services.EligibilityCalculationService;
import nic.meg.mcap.services.PaymentService;

/**
 * Handles Razorpay server-to-server webhook events.
 *
 * Configure the webhook URL in Razorpay Dashboard → Settings → Webhooks:
 *   https://yourdomain.com/webhook/razorpay
 *
 * Subscribe to these events:
 *   - payment.authorized   (payment authorized but NOT yet captured — rare with auto-capture)
 *   - payment.captured     (primary success event — money in your account)
 *   - payment.failed       (payment failed at bank/gateway level)
 *   - order.paid           (fires after all payments on an order complete)
 *   - refund.created       (refund initiated)
 *   - refund.processed     (refund successfully credited to customer)
 *   - refund.failed        (refund attempt failed)
 *   - dispute.created      (chargeback or dispute raised by customer)
 *   - dispute.won          (dispute resolved in merchant's favour)
 *   - dispute.lost         (dispute resolved against merchant)
 *
 * This acts as a safety net for cases where the frontend callback (/payment-callback)
 * fails to reach the server (network drop, tab close, etc.).
 *
 * Bank-level fields (RRN, UPI transaction ID, VPA, bank, method) are extracted
 * directly from the webhook payload's acquirer_data — no extra API call needed.
 */
@RestController
@RequestMapping("/webhook")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    @Value("${razorpay.webhook.secret}")
    private String webhookSecret;

    @Autowired private PaymentService                    paymentService;
    @Autowired private PaymentRepository                 paymentRepository;
    @Autowired private ApplicationRepository             applicationRepository;
    @Autowired private SeatAllotmentRepository           seatAllotmentRepository;
    @Autowired private ApplicationService                applicationService;
    @Autowired private ApplicationSubmissionService      submissionService;
    @Autowired private EligibilityCalculationService     eligibilityCalculationService;
    @Autowired private CounselingService                 counselingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────
    // MAIN WEBHOOK ENDPOINT
    // ─────────────────────────────────────────────────────────────────────
    @PostMapping("/razorpay")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        // Always return 200 quickly — Razorpay retries on non-2xx up to 24 hours
        if (signature == null || !verifySignature(payload, signature)) {
            log.warn("Razorpay webhook: invalid or missing signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(payload, Map.class);
            String eventType = (String) event.get("event");
            String accountId = (String) event.get("account_id");
            log.info("Razorpay webhook received: event={} account={}", eventType, accountId);

            switch (eventType != null ? eventType : "") {

                // ── PAYMENT EVENTS ──────────────────────────────────────
                case "payment.authorized" ->
                    // Payment authorized at bank but capture is pending (only if auto-capture is OFF)
                    // Usually no action needed if your account has auto-capture enabled.
                        handlePaymentAuthorized(event);

                case "payment.captured" ->
                    // Money has actually been captured — this is the definitive SUCCESS event
                        handlePaymentCaptured(event);

                case "payment.failed" ->
                    // Payment declined / timed out at bank
                        handlePaymentFailed(event);

                // ── ORDER EVENTS ─────────────────────────────────────────
                case "order.paid" ->
                    // All payments on the Razorpay order have completed (fires after payment.captured)
                    // Useful as a final consistency check; most logic should already be done by then
                        handleOrderPaid(event);

                // ── REFUND EVENTS ────────────────────────────────────────
                case "refund.created" ->
                    // A refund was initiated (may still be pending with the bank)
                        handleRefundCreated(event);

                case "refund.processed" ->
                    // Refund successfully credited to customer's account
                        handleRefundProcessed(event);

                case "refund.failed" ->
                    // Refund attempt failed — needs manual intervention
                        handleRefundFailed(event);

                case "refund.speed_changed" ->
                    // Refund processing speed was changed (e.g. normal → instant); informational only
                        log.info("Razorpay: refund.speed_changed received, no action needed");

                // ── DISPUTE / CHARGEBACK EVENTS ──────────────────────────
                case "dispute.created" ->
                    // Customer has raised a chargeback — you have ~7 days to submit evidence
                        handleDisputeCreated(event);

                case "dispute.won" ->
                    // Dispute resolved in your favour — amount will be re-credited
                        handleDisputeWon(event);

                case "dispute.lost" ->
                    // Dispute resolved against you — amount is deducted
                        handleDisputeLost(event);

                case "dispute.closed" ->
                    // Dispute closed without chargeback (withdrawn or resolved amicably)
                        log.info("Razorpay: dispute.closed received");

                // ── VIRTUAL ACCOUNT EVENTS (if you use VA-based payments) ─
                case "virtual_account.credited" ->
                        log.info("Razorpay: virtual_account.credited received — handle if using VAs");

                case "virtual_account.close" ->
                        log.info("Razorpay: virtual_account.close received");

                // ── TRANSFER / ROUTE EVENTS (if using Razorpay Route) ───
                case "transfer.created", "transfer.processed", "transfer.failed" ->
                        log.info("Razorpay: transfer event={} received — handle if using Route", eventType);

                default ->
                    // Unknown/new event type — safe to ignore; log for visibility
                        log.info("Razorpay: unhandled event type='{}' — no action taken", eventType);
            }

        } catch (Exception e) {
            // Do NOT return 5xx — that causes Razorpay to retry indefinitely.
            // Log the error and return 200 so Razorpay doesn't flood retries.
            log.error("Razorpay webhook: processing error for payload — returning 200 to prevent retry loop", e);
        }

        return ResponseEntity.ok("OK");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAYMENT EVENT HANDLERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * payment.authorized — payment is authorized at the bank but NOT yet captured.
     * Only fires if your Razorpay account has auto-capture DISABLED.
     * If auto-capture is ON (default), you will never see this event before payment.captured.
     */
    @SuppressWarnings("unchecked")
    private void handlePaymentAuthorized(Map<String, Object> event) {
        try {
            Map<String, Object> entity = extractPaymentEntity(event);
            String razorpayPaymentId   = (String) entity.get("id");
            String razorpayOrderId     = (String) entity.get("order_id");
            log.info("payment.authorized: paymentId={} orderId={}", razorpayPaymentId, razorpayOrderId);

            // Mark as AUTHORIZED in DB so the UI can show "Payment processing…"
            paymentRepository.findByRazorpayOrderId(razorpayOrderId).ifPresent(p -> {
                if ("PENDING".equals(p.getStatus())) {
                    p.setStatus("AUTHORIZED");
                    p.setRazorpayPaymentId(razorpayPaymentId);
                    paymentRepository.save(p);
                }
            });
        } catch (Exception e) {
            log.error("Error in handlePaymentAuthorized", e);
        }
    }

    /**
     * payment.captured — the primary, definitive success event.
     * Money has been captured and will be settled in your account.
     *
     * FIX 1: When notes.receipt is absent, fall back to looking up the Payment record
     * by razorpayOrderId so finalization always runs regardless of whether the
     * receipt note was embedded at order creation time.
     */
    @SuppressWarnings("unchecked")
    private void handlePaymentCaptured(Map<String, Object> event) {
        try {
            Map<String, Object> entity = extractPaymentEntity(event);

            String razorpayPaymentId = (String) entity.get("id");
            String razorpayOrderId   = (String) entity.get("order_id");
            Map<String, Object> notes = (Map<String, Object>) entity.get("notes");

            Integer amountPaise = (Integer) entity.get("amount");
            double  amount      = amountPaise != null ? amountPaise / 100.0 : 0.0;

            log.info("payment.captured: paymentId={} orderId={} amount={}", razorpayPaymentId, razorpayOrderId, amount);

            String receiptId = null;

            // Primary path: use receipt note embedded at order creation
            if (notes != null && notes.containsKey("receipt")) {
                receiptId = (String) notes.get("receipt");
                log.info("payment.captured: found receipt={} in notes for orderId={}", receiptId, razorpayOrderId);
            } else {
                // FIX 1: Fallback — look up the Payment record by razorpayOrderId
                // to recover the orderId (receipt) when notes are missing or empty.
                log.warn("payment.captured: no 'receipt' key in notes for orderId={} — falling back to DB lookup",
                        razorpayOrderId);
                receiptId = paymentRepository.findByRazorpayOrderId(razorpayOrderId)
                        .map(Payment::getOrderId)
                        .orElse(null);
                if (receiptId != null) {
                    log.info("payment.captured: recovered receiptId={} from DB for orderId={}", receiptId, razorpayOrderId);
                } else {
                    log.error("payment.captured: could not recover receiptId for orderId={} — finalization skipped",
                            razorpayOrderId);
                }
            }

            // FIX 2: snapshot whether this payment was ALREADY finalized *before* we call
            // fetchPaymentStatus() below — that call itself writes PAYMENT_SUCCESS onto the
            // Payment row as a side effect (via PaymentServiceImpl.updatePaymentStatus), which
            // was previously fooling the idempotency check further down into thinking
            // finalization had already happened when it was really just this webhook's own
            // write. That false-positive skip meant counselingService.acceptAllotment() /
            // slideUpAllotment() never ran, leaving seat_allotment stuck at PENDING even
            // though Payment.status showed PAYMENT_SUCCESS. Capturing the pre-existing status
            // first makes the idempotency check trustworthy again.
            boolean alreadyFinalized = receiptId != null
                    && paymentRepository.findByOrderId(receiptId)
                    .map(p -> "PAYMENT_SUCCESS".equals(p.getStatus()))
                    .orElse(false);

            // Sync latest status from Razorpay API (idempotent)
            paymentService.fetchPaymentStatus(razorpayOrderId);

            // Enrich the payment record with bank/UPI details from acquirer_data
            paymentRepository.findByRazorpayOrderId(razorpayOrderId).ifPresent(p -> {
                if (p.getRazorpayPaymentId() == null) {
                    p.setRazorpayPaymentId(razorpayPaymentId);
                }
                paymentService.enrichPaymentWithBankDetails(p, entity);
                paymentRepository.save(p);
            });

            if (receiptId != null) {
                if (alreadyFinalized) {
                    log.info("payment.captured: order {} already PAYMENT_SUCCESS prior to this webhook — skipping re-finalization", receiptId);
                } else {
                    finalizeSuccessfulPayment(receiptId, razorpayPaymentId, amount);
                }
            }

        } catch (Exception e) {
            log.error("Error in handlePaymentCaptured", e);
        }
    }

    /**
     * payment.failed — payment was declined or timed out.
     * Update the payment record so the user can retry.
     */
    @SuppressWarnings("unchecked")
    private void handlePaymentFailed(Map<String, Object> event) {
        try {
            Map<String, Object> entity         = extractPaymentEntity(event);
            String razorpayOrderId             = (String) entity.get("order_id");
            String razorpayPaymentId           = (String) entity.get("id");

            // Pull error details from the error_* fields in the entity
            String errorCode        = (String) entity.get("error_code");
            String errorDescription = (String) entity.get("error_description");
            String errorSource      = (String) entity.get("error_source");   // e.g. "bank", "gateway"
            String errorStep        = (String) entity.get("error_step");      // e.g. "payment_authentication"
            String errorReason      = (String) entity.get("error_reason");    // e.g. "payment_failed"

            log.warn("payment.failed: orderId={} paymentId={} code={} description={} source={} step={} reason={}",
                    razorpayOrderId, razorpayPaymentId,
                    errorCode, errorDescription, errorSource, errorStep, errorReason);

            paymentRepository.findByRazorpayOrderId(razorpayOrderId).ifPresent(p -> {
                p.setStatus("PAYMENT_FAILED");
                if (razorpayPaymentId != null) {
                    p.setRazorpayPaymentId(razorpayPaymentId);
                }
                paymentRepository.save(p);
            });

            // Also refresh from Razorpay API so status is consistent
            paymentService.fetchPaymentStatus(razorpayOrderId);

        } catch (Exception e) {
            log.error("Error in handlePaymentFailed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ORDER EVENT HANDLERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * order.paid — fires after all payments on a Razorpay order have completed.
     *
     * FIX 2: If the payment is still PENDING or AUTHORIZED at this point
     * (meaning payment.captured was missed or its finalization failed),
     * actively recover the receiptId from DB and call finalizeSuccessfulPayment()
     * instead of just logging a warning.
     */
    @SuppressWarnings("unchecked")
    private void handleOrderPaid(Map<String, Object> event) {
        try {
            Map<String, Object> webhookPayload = (Map<String, Object>) event.get("payload");
            Map<String, Object> orderObj       = (Map<String, Object>) webhookPayload.get("order");
            Map<String, Object> orderEntity    = (Map<String, Object>) orderObj.get("entity");
            Map<String, Object> paymentObj     = (Map<String, Object>) webhookPayload.get("payment");
            Map<String, Object> paymentEntity  = paymentObj != null ? (Map<String, Object>) paymentObj.get("entity") : null;

            String razorpayOrderId   = (String) orderEntity.get("id");
            String razorpayPaymentId = paymentEntity != null ? (String) paymentEntity.get("id") : null;
            Integer amountPaise      = paymentEntity != null ? (Integer) paymentEntity.get("amount") : null;
            double  amount           = amountPaise != null ? amountPaise / 100.0 : 0.0;

            log.info("order.paid: orderId={} paymentId={}", razorpayOrderId, razorpayPaymentId);

            paymentRepository.findByRazorpayOrderId(razorpayOrderId).ifPresent(p -> {
                if ("PAYMENT_SUCCESS".equals(p.getStatus())) {
                    log.info("order.paid: order {} already finalized — no action needed", razorpayOrderId);
                    return;
                }

                // FIX 2: Payment is still stuck in PENDING or AUTHORIZED —
                // payment.captured either wasn't delivered or its finalization failed.
                // Use order.paid as a hard recovery path.
                log.warn("order.paid: payment still in {} state for orderId={} — triggering forced finalization",
                        p.getStatus(), razorpayOrderId);

                // Sync from Razorpay API first
                paymentService.fetchPaymentStatus(razorpayOrderId);

                String receiptId = p.getOrderId();
                if (receiptId != null && razorpayPaymentId != null) {
                    finalizeSuccessfulPayment(receiptId, razorpayPaymentId, amount);
                } else {
                    log.error("order.paid: missing receiptId={} or razorpayPaymentId={} — manual recovery needed",
                            receiptId, razorpayPaymentId);
                }
            });

        } catch (Exception e) {
            log.error("Error in handleOrderPaid", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // REFUND EVENT HANDLERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * refund.created — a refund has been initiated (may take 5–7 business days to process).
     */
    @SuppressWarnings("unchecked")
    private void handleRefundCreated(Map<String, Object> event) {
        try {
            Map<String, Object> entity = extractRefundEntity(event);
            String refundId    = (String) entity.get("id");
            String paymentId   = (String) entity.get("payment_id");
            Integer amountPaise = (Integer) entity.get("amount");
            double  amount      = amountPaise != null ? amountPaise / 100.0 : 0.0;

            log.info("refund.created: refundId={} paymentId={} amount={}", refundId, paymentId, amount);

            // Update the payment record to reflect the refund is in progress
            paymentRepository.findByRazorpayPaymentId(paymentId).ifPresent(p -> {
                p.setStatus("REFUND_INITIATED");
                paymentRepository.save(p);
            });

        } catch (Exception e) {
            log.error("Error in handleRefundCreated", e);
        }
    }

    /**
     * refund.processed — refund has been successfully credited to the customer.
     */
    @SuppressWarnings("unchecked")
    private void handleRefundProcessed(Map<String, Object> event) {
        try {
            Map<String, Object> entity = extractRefundEntity(event);
            String refundId  = (String) entity.get("id");
            String paymentId = (String) entity.get("payment_id");

            log.info("refund.processed: refundId={} paymentId={}", refundId, paymentId);

            paymentRepository.findByRazorpayPaymentId(paymentId).ifPresent(p -> {
                p.setStatus("REFUNDED");
                paymentRepository.save(p);
            });

        } catch (Exception e) {
            log.error("Error in handleRefundProcessed", e);
        }
    }

    /**
     * refund.failed — the refund attempt failed at the bank.
     * Requires manual re-initiation from Razorpay Dashboard.
     */
    @SuppressWarnings("unchecked")
    private void handleRefundFailed(Map<String, Object> event) {
        try {
            Map<String, Object> entity = extractRefundEntity(event);
            String refundId  = (String) entity.get("id");
            String paymentId = (String) entity.get("payment_id");

            log.error("refund.failed: refundId={} paymentId={} — MANUAL ACTION REQUIRED", refundId, paymentId);

            paymentRepository.findByRazorpayPaymentId(paymentId).ifPresent(p -> {
                p.setStatus("REFUND_FAILED");
                paymentRepository.save(p);
            });

            // TODO: send alert to admin (email/SMS) since this requires manual retry from dashboard

        } catch (Exception e) {
            log.error("Error in handleRefundFailed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DISPUTE / CHARGEBACK EVENT HANDLERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * dispute.created — customer has raised a chargeback.
     * You have a limited window (typically 7–10 days) to submit evidence via Razorpay Dashboard.
     */
    @SuppressWarnings("unchecked")
    private void handleDisputeCreated(Map<String, Object> event) {
        try {
            Map<String, Object> webhookPayload = (Map<String, Object>) event.get("payload");
            Map<String, Object> disputeObj     = (Map<String, Object>) webhookPayload.get("dispute");
            Map<String, Object> entity         = (Map<String, Object>) disputeObj.get("entity");

            String disputeId = (String) entity.get("id");
            String paymentId = (String) entity.get("payment_id");
            String reason    = (String) entity.get("reason_code");

            log.error("dispute.created: disputeId={} paymentId={} reason={} — SUBMIT EVIDENCE IN RAZORPAY DASHBOARD",
                    disputeId, paymentId, reason);

            paymentRepository.findByRazorpayPaymentId(paymentId).ifPresent(p -> {
                p.setStatus("DISPUTED");
                paymentRepository.save(p);
            });

            // TODO: send urgent alert to admin team

        } catch (Exception e) {
            log.error("Error in handleDisputeCreated", e);
        }
    }

    /**
     * dispute.won — dispute resolved in your favour. Amount will be re-credited.
     */
    @SuppressWarnings("unchecked")
    private void handleDisputeWon(Map<String, Object> event) {
        try {
            Map<String, Object> webhookPayload = (Map<String, Object>) event.get("payload");
            Map<String, Object> disputeObj     = (Map<String, Object>) webhookPayload.get("dispute");
            Map<String, Object> entity         = (Map<String, Object>) disputeObj.get("entity");

            String paymentId = (String) entity.get("payment_id");
            log.info("dispute.won: paymentId={} — dispute resolved in merchant's favour", paymentId);

            paymentRepository.findByRazorpayPaymentId(paymentId).ifPresent(p -> {
                p.setStatus("PAYMENT_SUCCESS"); // Restore to successful
                paymentRepository.save(p);
            });

        } catch (Exception e) {
            log.error("Error in handleDisputeWon", e);
        }
    }

    /**
     * dispute.lost — dispute resolved against you. Amount is deducted from your settlement.
     */
    @SuppressWarnings("unchecked")
    private void handleDisputeLost(Map<String, Object> event) {
        try {
            Map<String, Object> webhookPayload = (Map<String, Object>) event.get("payload");
            Map<String, Object> disputeObj     = (Map<String, Object>) webhookPayload.get("dispute");
            Map<String, Object> entity         = (Map<String, Object>) disputeObj.get("entity");

            String paymentId = (String) entity.get("payment_id");
            log.error("dispute.lost: paymentId={} — amount deducted from settlement. MANUAL REVIEW REQUIRED", paymentId);

            paymentRepository.findByRazorpayPaymentId(paymentId).ifPresent(p -> {
                p.setStatus("CHARGEBACK_LOST");
                paymentRepository.save(p);
            });

        } catch (Exception e) {
            log.error("Error in handleDisputeLost", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // BUSINESS LOGIC FINALIZATION
    // ─────────────────────────────────────────────────────────────────────

    private void finalizeSuccessfulPayment(String receiptOrderId,
                                           String razorpayPaymentId,
                                           double amount) {
        try {
            paymentService.updatePaymentStatus(receiptOrderId, "PAYMENT_SUCCESS");

            if (receiptOrderId.startsWith("APP_")) {
                Long applicationId = Long.parseLong(receiptOrderId.split("_")[1]);
                Application app    = applicationRepository.findById(applicationId).orElseThrow();
                String applicantNo = app.getApplicant().getApplicantNo();

                applicationService.confirmPayment(applicationId, applicantNo, BigDecimal.valueOf(amount));

                app = applicationRepository.findById(applicationId).orElseThrow();
                app.setTransactionId(razorpayPaymentId.length() > 100
                        ? razorpayPaymentId.substring(0, 100) : razorpayPaymentId);
                applicationRepository.save(app);

                submissionService.finalizeApplicationSubmission(app);
                eligibilityCalculationService.calculateAndSaveEligibility(app);

            } else if (receiptOrderId.startsWith("SEAT_") || receiptOrderId.startsWith("SLIDEUP_")) {
                Long allotmentId    = Long.parseLong(receiptOrderId.split("_")[1]);
                SeatAllotment allot = seatAllotmentRepository.findById(allotmentId).orElseThrow();
                String applicantNo  = allot.getApplicant().getApplicantNo();

                // Status flips PENDING -> ACCEPTED / SLIDE_UP only here, on confirmed payment —
                // never before. initiate-seat-fee requires PENDING, so the allotment is
                // guaranteed to still be PENDING at this point.
                //
                // FIX 3: this branch previously only handled SEAT_ orders and silently did
                // nothing for SLIDEUP_ orders, so a slide-up payment confirmed via webhook
                // (rather than the browser callback) never actually moved the allotment to
                // SLIDE_UP. Mirrors PaymentController.finalizeSuccessfulPayment, which already
                // handles both cases correctly.
                if (receiptOrderId.startsWith("SLIDEUP_")) {
                    counselingService.slideUpAllotment(applicantNo, allotmentId);
                    log.info("Slide Up fee paid (webhook) for allotment {} by applicant {} — status set to SLIDE_UP", allotmentId, applicantNo);
                } else {
                    counselingService.acceptAllotment(applicantNo, allotmentId);
                    log.info("Seat acceptance fee paid (webhook), allotment {} accepted for applicant {}", allotmentId, applicantNo);
                }
            }
        } catch (Exception e) {
            log.error("Error in finalizeSuccessfulPayment for receiptOrderId={}", receiptOrderId, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAYLOAD EXTRACTION HELPERS
    // ─────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPaymentEntity(Map<String, Object> event) {
        Map<String, Object> webhookPayload = (Map<String, Object>) event.get("payload");
        Map<String, Object> paymentObj     = (Map<String, Object>) webhookPayload.get("payment");
        return (Map<String, Object>) paymentObj.get("entity");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRefundEntity(Map<String, Object> event) {
        Map<String, Object> webhookPayload = (Map<String, Object>) event.get("payload");
        Map<String, Object> refundObj      = (Map<String, Object>) webhookPayload.get("refund");
        return (Map<String, Object>) refundObj.get("entity");
    }

    // ─────────────────────────────────────────────────────────────────────
    // SIGNATURE VERIFICATION
    // HMAC-SHA256(webhookSecret, rawBody) must equal X-Razorpay-Signature
    // ─────────────────────────────────────────────────────────────────────
    private boolean verifySignature(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().equals(signature);
        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }
}