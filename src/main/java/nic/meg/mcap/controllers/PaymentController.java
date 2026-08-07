package nic.meg.mcap.controllers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import nic.meg.mcap.dto.response.InstituteSeatFeeStructureResponseDTO;
import nic.meg.mcap.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import nic.meg.mcap.dto.response.ProgrammePreferenceResponseDTO;
import nic.meg.mcap.entities.Address;
import nic.meg.mcap.entities.Applicant;
import nic.meg.mcap.entities.Application;
import nic.meg.mcap.entities.Payment;
import nic.meg.mcap.entities.SeatAllotment;
import nic.meg.mcap.enums.AllotmentStatus;
import nic.meg.mcap.repositories.AddressRepository;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.repositories.PaymentRepository;
import nic.meg.mcap.repositories.SeatAllotmentRepository;
import nic.meg.mcap.repositories.SeatAllotmentReleaseRepository;

@Controller
@RequestMapping("/applicants/payment")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    // ─── TEST OVERRIDE ───────────────────────────────────────────────────────────────────
    // Set to true to charge ₹1 for all payments (Razorpay test mode).
    // REMOVE or set to false before going to production.
    private static final boolean TEST_OVERRIDE_AMOUNT = false;
    private static final BigDecimal TEST_AMOUNT = new BigDecimal("1.00");
    // Slide Up is a flat, fixed fee — NOT the institute's admission/seat acceptance fee.
    private static final BigDecimal SLIDE_UP_FEE = new BigDecimal("1000.00");
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Autowired private PaymentService                paymentService;
    @Autowired private ApplicationRepository         applicationRepository;
    @Autowired private PaymentRepository             paymentRepository;
    @Autowired private SeatAllotmentRepository       seatAllotmentRepository;
    @Autowired private ProgrammePreferenceService    preferenceService;
    @Autowired private ApplicationSubmissionService  submissionService;
    @Autowired private CounselingService             counselingService;
    @Autowired private EligibilityCalculationService eligibilityCalculationService;
    @Autowired private ApplicationService            applicationService;
    @Autowired private PdfGenerationService          pdfGenerationService;
    @Autowired private AddressRepository             addressRepository;
    @Autowired private InstituteSeatFeeService       instituteSeatFeeService;
    @Autowired private SeatAllotmentReleaseRepository releaseRepository;

    private static final short MEGHALAYA_STATE_CODE = 17;

    // ─────────────────────────────────────────────────────────────────────────────────────
    // SHOW PAYMENT PAGE
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Shows the Razorpay checkout page.
     *
     * On every page load we do a two-step status check BEFORE showing the modal:
     *
     *  Step 1 — DB check:
     *    • If already PAYMENT_SUCCESS  → redirect to payment-status (no need to pay again)
     *    • If already PAYMENT_FAILED   → redirect to dashboard with a retry message
     *    • If AUTHORIZED / PENDING     → continue to step 2
     *
     *  Step 2 — Razorpay API check (only when DB says PENDING or AUTHORIZED):
     *    • Calls fetchPaymentStatus(razorpayOrderId) → gets order status from Razorpay
     *    • If Razorpay says "paid"      → finalize in DB, redirect to payment-status
     *    • If Razorpay says "attempted" → a payment is in-flight; show page normally
     *    • If Razorpay says "created"   → no attempt yet; show page normally
     *
     * This prevents duplicate payments and catches the edge case where the webhook
     * fired before the user's browser redirected back to our server.
     */
    @GetMapping("/make-payment")
    public String showPaymentPage(
            @RequestParam(required = false) String razorpayOrderId,
            @RequestParam(required = false) String keyId,
            @RequestParam(required = false) String receiptId,
            Model model,
            RedirectAttributes redirectAttributes) {

        // Guard: someone navigated here directly without going through initiate-*
        if (razorpayOrderId == null || keyId == null || receiptId == null) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Invalid payment session. Please start over from your dashboard.");
            return "redirect:/applicants/dashboard";
        }

        // ── STEP 1: DB CHECK ──────────────────────────────────────────────────────────
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(receiptId);
        if (paymentOpt.isPresent()) {
            String dbStatus = paymentOpt.get().getStatus();

            if ("PAYMENT_SUCCESS".equals(dbStatus)) {
                // Already paid — redirect to status page, no modal needed
                logger.info("showPaymentPage: order {} already PAYMENT_SUCCESS in DB, redirecting", receiptId);
                redirectAttributes.addFlashAttribute("infoMessage",
                        "This payment has already been completed successfully.");
                return "redirect:/applicants/payment/payment-status"
                        + "?order_id=" + receiptId
                        + "&payment_id=" + paymentOpt.get().getRazorpayPaymentId();
            }

            if ("PAYMENT_FAILED".equals(dbStatus)) {
                // Previously failed — let them go back and retry from dashboard
                logger.info("showPaymentPage: order {} is PAYMENT_FAILED in DB", receiptId);
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Your previous payment attempt failed. Please try again.");
                return "redirect:/applicants/dashboard";
            }

            // ── STEP 2: RAZORPAY API CHECK (only for PENDING / AUTHORIZED) ────────────
            // DB says the payment isn't settled yet — verify with Razorpay as the source of truth.
            try {
                Map<String, Object> razorpayOrder = paymentService.fetchPaymentStatus(razorpayOrderId);
                String rpStatus = razorpayOrder != null ? (String) razorpayOrder.get("status") : null;
                logger.info("showPaymentPage: Razorpay order status for {} = {}", razorpayOrderId, rpStatus);

                if ("paid".equals(rpStatus)) {
                    // Razorpay says paid but our DB hasn't caught up yet
                    // (webhook may be slightly delayed) — sync and redirect
                    logger.warn("showPaymentPage: Razorpay says 'paid' but DB is {}. Syncing now.", dbStatus);
                    paymentService.updatePaymentStatus(receiptId, "PAYMENT_SUCCESS");
                    Payment p = paymentOpt.get();
                    return "redirect:/applicants/payment/payment-status"
                            + "?order_id=" + receiptId
                            + "&payment_id=" + (p.getRazorpayPaymentId() != null ? p.getRazorpayPaymentId() : "");
                }
                // "attempted" or "created" → fall through and show the payment modal normally

            } catch (Exception e) {
                // Non-fatal — if Razorpay API is unreachable, still show the page
                logger.warn("showPaymentPage: could not reach Razorpay API for order {} — proceeding anyway", razorpayOrderId, e);
            }
        }
        // No payment record yet (very first load) → show modal immediately

        model.addAttribute("razorpayOrderId", razorpayOrderId);
        model.addAttribute("razorpayKeyId",   keyId);
        model.addAttribute("receiptId",        receiptId);
        return "applicant/payment/make-payment";
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // INITIATE APPLICATION FEE
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * FIX 3a: Block duplicate application fee payment initiation.
     * If a PAYMENT_SUCCESS record already exists for this applicationId,
     * redirect the user back to the dashboard with a friendly message
     * instead of creating a new Razorpay order.
     */
    @PostMapping("/initiate-application-fee")
    public String initiateApplicationFee(@RequestParam("applicationId") Long applicationId,
                                         Authentication auth,
                                         HttpServletRequest httpRequest,
                                         RedirectAttributes redirectAttributes) {
        try {
            Application app = applicationRepository.findById(applicationId)
                    .filter(a -> a.getApplicant().getApplicantNo().equals(auth.getName()))
                    .orElseThrow(() -> new SecurityException("Application not found or unauthorized."));

            // FIX 3a: Duplicate payment guard — check if this application already has a
            // successful payment before creating a new Razorpay order.
            if (paymentRepository.existsByApplicationIdAndStatus(applicationId, "PAYMENT_SUCCESS")) {
                logger.warn("initiateApplicationFee: applicationId={} already has PAYMENT_SUCCESS — blocking duplicate",
                        applicationId);
                redirectAttributes.addFlashAttribute("infoMessage",
                        "Payment for this application has already been completed successfully. " +
                                "No further payment is required.");
                return "redirect:/applicants/dashboard";
            }

            Applicant applicant = app.getApplicant();

            boolean hasDomicile = Boolean.TRUE.equals(applicant.getHasDomicileCertificate());
            String category = applicant.getCommunityCategory() != null ?
                    applicant.getCommunityCategory().getCategoryCode().trim().toUpperCase() : "GEN";

            boolean permanentAddressInMeghalaya = addressRepository
                    .findByEntityIdAndAddressType(applicant.getApplicantId(), "PERMANENT")
                    .map(addr -> addr.getState() != null && addr.getState().getStateCode() == MEGHALAYA_STATE_CODE)
                    .orElse(false);

            boolean isMeghalayaResident = hasDomicile || permanentAddressInMeghalaya;

            BigDecimal totalFee;
            if (TEST_OVERRIDE_AMOUNT) {
                totalFee = TEST_AMOUNT;
            } else if (!isMeghalayaResident) {
                totalFee = new BigDecimal("1000.00");
            } else if ("ST".equals(category) || "SC".equals(category)) {
                totalFee = new BigDecimal("200.00");
            } else {
                totalFee = new BigDecimal("500.00");
            }

            if (totalFee.compareTo(BigDecimal.ZERO) <= 0) {
                redirectAttributes.addFlashAttribute("errorMessage", "Fee evaluation failed.");
                return "redirect:/applicants/dashboard";
            }

            String orderId = "APP_" + applicationId + "_" + System.currentTimeMillis();

            String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                    .replacePath(null).build().toUriString();
            String returnUrl = baseUrl + "/applicants/payment/payment-callback";

            String customerName  = applicant.getFirstName() + " " + (applicant.getLastName() != null ? applicant.getLastName() : "");
            String customerPhone = applicant.getPhoneNumber() != null ? applicant.getPhoneNumber() : "9999999999";
            String customerEmail = applicant.getEmail()      != null ? applicant.getEmail()       : "no-email@domain.com";

            Map<String, String> response = paymentService.createOrder(
                    totalFee.doubleValue(), applicant.getApplicantNo(), customerName.trim(),
                    customerEmail, customerPhone, returnUrl, orderId);

            return "redirect:/applicants/payment/make-payment"
                    + "?razorpayOrderId=" + response.get("orderId")
                    + "&keyId="           + response.get("keyId")
                    + "&receiptId="       + response.get("receiptId");

        } catch (Exception e) {
            logger.error("Error initiating application fee payment", e);
            redirectAttributes.addFlashAttribute("errorMessage", "Could not initiate payment. Please try again.");
            return "redirect:/applicants/dashboard";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // INITIATE SEAT FEE
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * FIX 3b: Block duplicate seat fee payment initiation.
     * If a PAYMENT_SUCCESS record already exists for this allotmentId,
     * redirect the user back to the dashboard with a friendly message.
     */
    @PostMapping("/initiate-seat-fee")
    public String initiateSeatFee(@RequestParam("allotmentId") Long allotmentId,
                                  @RequestParam(value = "isSlideUp", required = false, defaultValue = "false") boolean isSlideUp,
                                  Authentication auth,
                                  HttpServletRequest httpRequest,
                                  RedirectAttributes redirectAttributes) {
        try {
            SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                    .orElseThrow(() -> new RuntimeException("Allotment not found"));

            if (!allotment.getApplicant().getApplicantNo().equals(auth.getName())) {
                throw new SecurityException("Unauthorized access to allotment.");
            }

            // FIX 4: block starting a NEW seat-fee / slide-up-fee payment once the admin has
            // closed payments for this admission window + round + phase. This does not affect
            // anyone who already paid (ACCEPTED / SLIDE_UP never call this endpoint again) —
            // it only stops applicants still at PENDING from initiating a fresh payment after
            // the phase's payment window has ended.
            boolean paymentsClosed = releaseRepository
                    .findByAdmissionWindowIdAndRoundTypeAndPhaseNo(
                            allotment.getAdmissionWindow().getAdmissionId(),
                            allotment.getRoundType(),
                            allotment.getPhaseNo())
                    .map(nic.meg.mcap.entities.SeatAllotmentRelease::isPaymentsClosed)
                    .orElse(false);

            if (paymentsClosed) {
                logger.warn("initiateSeatFee: allotmentId={} — payments are closed for window={} round={} phase={}, blocking",
                        allotmentId, allotment.getAdmissionWindow().getAdmissionId(),
                        allotment.getRoundType(), allotment.getPhaseNo());
                redirectAttributes.addFlashAttribute("errorMessage",
                        "The payment window for this admission round/phase has closed. Please check your dashboard for further instructions.");
                return "redirect:/applicants/dashboard";
            }

            // FIX 3b: Duplicate payment guard — check if this allotment already has a
            // successful payment before creating a new Razorpay order.
            if (paymentRepository.existsByAllotmentIdAndStatus(allotmentId, "PAYMENT_SUCCESS")) {
                logger.warn("initiateSeatFee: allotmentId={} already has PAYMENT_SUCCESS — blocking duplicate",
                        allotmentId);
                redirectAttributes.addFlashAttribute("infoMessage",
                        "Seat acceptance fee for this allotment has already been paid successfully. " +
                                "No further payment is required.");
                return "redirect:/applicants/dashboard";
            }

            // Guard against creating a second concurrent Razorpay order while an earlier
            // one for this allotment is still unresolved (e.g. connection dropped mid-checkout,
            // bank confirmation still pending, or the user simply hasn't finished paying yet).
            // Orders older than 15 minutes are treated as abandoned/expired and allowed to be
            // superseded by a fresh one, since Razorpay orders don't stay payable forever.
            java.util.List<Payment> inProgress = paymentRepository.findInProgressPaymentsForAllotment(allotmentId);
            if (!inProgress.isEmpty()) {
                Payment latestInProgress = inProgress.get(0);
                boolean stillFresh = latestInProgress.getCreatedAt() != null
                        && latestInProgress.getCreatedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(15));
                if (stillFresh) {
                    logger.warn("initiateSeatFee: allotmentId={} has an in-progress payment (orderId={}) created at {} — blocking duplicate",
                            allotmentId, latestInProgress.getOrderId(), latestInProgress.getCreatedAt());
                    redirectAttributes.addFlashAttribute("infoMessage",
                            "A payment for this allotment is already in progress and may still be confirming with your bank. " +
                                    "Please wait a few minutes and check your dashboard before trying again. " +
                                    "If your bank has already deducted the amount, do not attempt payment a second time.");
                    return "redirect:/applicants/dashboard";
                }
                logger.info("initiateSeatFee: allotmentId={} had a stale in-progress payment (orderId={}, created {}) — allowing a fresh attempt",
                        allotmentId, latestInProgress.getOrderId(), latestInProgress.getCreatedAt());
            }

            AllotmentStatus currentStatus = allotment.getStatus();
            boolean validStatus = isSlideUp
                    ? currentStatus == AllotmentStatus.PENDING
                    : currentStatus == AllotmentStatus.PENDING;

            if (!validStatus) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Allotment is no longer in the correct state for payment.");
                return "redirect:/applicants/dashboard";
            }

            // Slide Up fee is a flat, one-time charge per applicant per admission cycle —
            // if they've already paid it once (on an earlier allotment/round), sliding up
            // again is free: just release the previous hold and confirm the new one directly,
            // no Razorpay redirect needed.
            if (isSlideUp && paymentRepository.existsSuccessfulSlideUpPaymentForApplicant(
                    auth.getName(), "PAYMENT_SUCCESS")) {
                counselingService.slideUpAllotment(auth.getName(), allotmentId);
                redirectAttributes.addFlashAttribute("infoMessage",
                        "You've already paid the one-time Slide Up fee this admission cycle — " +
                                "your seat hold has been moved to this preference at no extra charge.");
                return "redirect:/applicants/dashboard";
            }

            Integer programmeOfferedId = allotment.getProgrammeOffered() != null
                    ? allotment.getProgrammeOffered().getProgrammeOfferedId()
                    : null;

            BigDecimal resolvedFee;
            if (TEST_OVERRIDE_AMOUNT) {
                resolvedFee = TEST_AMOUNT;
            } else if (isSlideUp) {
                resolvedFee = SLIDE_UP_FEE;
            } else {
                resolvedFee = (programmeOfferedId != null)
                        ? instituteSeatFeeService.resolveAcceptanceFee(programmeOfferedId)
                        : null;

                if (resolvedFee == null || resolvedFee.compareTo(BigDecimal.ZERO) <= 0) {
                    redirectAttributes.addFlashAttribute("errorMessage",
                            "No seat acceptance fee has been configured for your allotted programme. Please contact the institute.");
                    return "redirect:/applicants/dashboard";
                }

                // Slide-Up fee credit: if this applicant already paid the flat 1000 slide-up
                // fee earlier in this admission cycle, it's adjusted against whichever seat
                // they finally accept — the new higher-preference one, or the one they
                // retained if no better preference came through. They only owe the remainder.
                if (!TEST_OVERRIDE_AMOUNT && paymentRepository.existsSuccessfulSlideUpPaymentForApplicant(
                        auth.getName(), "PAYMENT_SUCCESS")) {
                    BigDecimal afterCredit = resolvedFee.subtract(SLIDE_UP_FEE);
                    if (afterCredit.compareTo(BigDecimal.ZERO) <= 0) {
                        // Credit fully covers the admission fee — no payment gateway needed.
                        Payment coveredPayment = new Payment();
                        coveredPayment.setOrderId("SEAT_" + allotmentId + "_" + System.currentTimeMillis());
                        coveredPayment.setAmount(0.0);
                        coveredPayment.setCurrency("INR");
                        coveredPayment.setCustomerId(auth.getName());
                        coveredPayment.setStatus("PAYMENT_SUCCESS");
                        coveredPayment.setCreatedAt(java.time.LocalDateTime.now());
                        coveredPayment.setUpdatedAt(java.time.LocalDateTime.now());
                        paymentRepository.save(coveredPayment);

                        counselingService.acceptAllotment(auth.getName(), allotmentId);
                        redirectAttributes.addFlashAttribute("infoMessage",
                                "Your previously-paid Slide Up fee fully covered this admission fee — " +
                                        "your seat is confirmed at no additional charge.");
                        return "redirect:/applicants/dashboard";
                    }
                    resolvedFee = afterCredit;
                }
            }

            double acceptanceFee = resolvedFee.doubleValue();
            Applicant applicant  = allotment.getApplicant();

            String prefix  = isSlideUp ? "SLIDEUP_" : "SEAT_";
            String orderId = prefix + allotmentId + "_" + System.currentTimeMillis();

            String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                    .replacePath(null).build().toUriString();
            String returnUrl = baseUrl + "/applicants/payment/payment-callback";

            String customerName  = applicant.getFirstName() + " " + (applicant.getLastName() != null ? applicant.getLastName() : "");
            String customerPhone = applicant.getPhoneNumber() != null ? applicant.getPhoneNumber() : "9999999999";
            String customerEmail = applicant.getEmail()      != null ? applicant.getEmail()       : "no-email@domain.com";

            Map<String, String> response = paymentService.createOrder(
                    acceptanceFee, applicant.getApplicantNo(), customerName.trim(),
                    customerEmail, customerPhone, returnUrl, orderId);

            return "redirect:/applicants/payment/make-payment"
                    + "?razorpayOrderId=" + response.get("orderId")
                    + "&keyId="           + response.get("keyId")
                    + "&receiptId="       + response.get("receiptId");

        } catch (Exception e) {
            logger.error("Error initiating seat fee payment", e);
            redirectAttributes.addFlashAttribute("errorMessage", "Could not initiate seat fee payment.");
            return "redirect:/applicants/dashboard";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // PAYMENT CALLBACK (frontend → server after Razorpay modal closes)
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Razorpay frontend callback — called after the modal closes (success or failure).
     * Signature is verified server-side before any business logic runs.
     *
     * Idempotency guard: if this order is already PAYMENT_SUCCESS in the DB
     * (e.g. webhook arrived first, or browser double-submitted), we skip
     * finalizeSuccessfulPayment() and go straight to the status page.
     */
    @PostMapping("/payment-callback")
    public String paymentCallback(
            @RequestParam("razorpay_order_id")   String razorpayOrderId,
            @RequestParam("razorpay_payment_id") String razorpayPaymentId,
            @RequestParam("razorpay_signature")  String razorpaySignature,
            @RequestParam("receipt_id")           String receiptId,
            RedirectAttributes redirectAttributes) {

        // ── IDEMPOTENCY: skip if already finalized ────────────────────────────────────
        Optional<Payment> existing = paymentRepository.findByOrderId(receiptId);
        if (existing.isPresent() && "PAYMENT_SUCCESS".equals(existing.get().getStatus())) {
            logger.info("paymentCallback: order {} already PAYMENT_SUCCESS — skipping re-finalization", receiptId);
            return "redirect:/applicants/payment/payment-status"
                    + "?order_id=" + receiptId
                    + "&payment_id=" + razorpayPaymentId;
        }

        // ── SIGNATURE VERIFICATION ────────────────────────────────────────────────────
        boolean valid = paymentService.verifyPaymentSignature(
                razorpayOrderId, razorpayPaymentId, razorpaySignature);

        if (!valid) {
            logger.warn("Invalid Razorpay signature for order {}", razorpayOrderId);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Payment verification failed. Please contact support.");
            return "redirect:/applicants/dashboard";
        }

        // ── FINALIZE ──────────────────────────────────────────────────────────────────
        paymentService.finalizePaymentRecord(receiptId, razorpayPaymentId, razorpaySignature);
        paymentService.updatePaymentStatus(receiptId, "PAYMENT_SUCCESS");
        finalizeSuccessfulPayment(receiptId, razorpayPaymentId);

        return "redirect:/applicants/payment/payment-status?order_id=" + receiptId
                + "&payment_id=" + razorpayPaymentId;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // PAYMENT STATUS PAGE
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Loads the real payment status from DB instead of hardcoding PAYMENT_SUCCESS.
     * Handles all possible statuses: PAYMENT_SUCCESS, PAYMENT_FAILED, PENDING,
     * AUTHORIZED, REFUND_INITIATED, REFUNDED, REFUND_FAILED, DISPUTED,
     * CHARGEBACK_LOST, UNKNOWN.
     */
    @GetMapping("/payment-status")
    public String paymentStatus(@RequestParam("order_id") String orderId,
                                @RequestParam(required = false) String payment_id,
                                Model model) {
        String status = paymentRepository.findByOrderId(orderId)
                .map(Payment::getStatus)
                .orElse("UNKNOWN");

        logger.info("paymentStatus page: orderId={} status={}", orderId, status);

        model.addAttribute("orderId",   orderId);
        model.addAttribute("paymentId", payment_id);
        model.addAttribute("status",    status);
        return "applicant/payment/payment-status";
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // SEAT FEE STRUCTURE (AJAX)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/seat-fee-structure/{programmeOfferedId}")
    @ResponseBody
    public ResponseEntity<?> getSeatFeeStructure(@PathVariable Integer programmeOfferedId,
                                                 @RequestParam(value = "isSlideUp", required = false, defaultValue = "false") boolean isSlideUp,
                                                 Authentication auth) {
        try {
            if (isSlideUp) {
                return ResponseEntity.ok(Map.of(
                        "particulars", List.of(Map.of("particularName", "Slide Up Fee (flat, one-time)", "amount", SLIDE_UP_FEE)),
                        "totalAmount", SLIDE_UP_FEE));
            }
            InstituteSeatFeeStructureResponseDTO structure =
                    instituteSeatFeeService.resolveAcceptanceFeeStructure(programmeOfferedId);
            if (structure == null) {
                return ResponseEntity.ok(Map.of("particulars", List.of()));
            }

            if (auth != null && paymentRepository.existsSuccessfulSlideUpPaymentForApplicant(auth.getName(), "PAYMENT_SUCCESS")) {
                BigDecimal total = structure.getTotalAmount() != null ? structure.getTotalAmount() : BigDecimal.ZERO;
                BigDecimal credit = SLIDE_UP_FEE.min(total);
                BigDecimal newTotal = total.subtract(credit);

                List<Map<String, Object>> particularsWithCredit = new java.util.ArrayList<>();
                if (structure.getParticulars() != null) {
                    structure.getParticulars().forEach(p -> particularsWithCredit.add(
                            Map.of("particularName", p.getParticularName(), "amount", p.getAmount())));
                }
                particularsWithCredit.add(Map.of(
                        "particularName", "Less: Slide Up Fee already paid (adjusted)",
                        "amount", credit.negate()));

                return ResponseEntity.ok(Map.of(
                        "particulars", particularsWithCredit,
                        "totalAmount", newTotal));
            }

            return ResponseEntity.ok(structure);
        } catch (Exception e) {
            logger.error("Error loading seat fee structure for programmeOffered {}", programmeOfferedId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Could not load fee structure"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // RECEIPT DOWNLOAD
    // ─────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/receipt/{id}")
    public org.springframework.http.ResponseEntity<byte[]> downloadReceipt(
            @PathVariable("id") Long applicationId, Authentication auth) {
        try {
            byte[] pdfBytes = pdfGenerationService.generateReceiptPdf(applicationId, auth.getName());
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("inline", "Payment-Receipt-" + applicationId + ".pdf");
            return new org.springframework.http.ResponseEntity<>(pdfBytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (IllegalStateException e) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // SEAT / ADMISSION FEE RECEIPT DOWNLOAD
    // ─────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/seat-receipt/{allotmentId}")
    public org.springframework.http.ResponseEntity<byte[]> downloadSeatFeeReceipt(
            @PathVariable("allotmentId") Long allotmentId, Authentication auth) {
        try {
            byte[] pdfBytes = pdfGenerationService.generateSeatFeeReceiptPdf(allotmentId, auth.getName());
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("inline", "Admission-Fee-Receipt-" + allotmentId + ".pdf");
            return new org.springframework.http.ResponseEntity<>(pdfBytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (IllegalStateException e) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        } catch (SecurityException e) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void finalizeSuccessfulPayment(String receiptOrderId, String razorpayPaymentId) {
        try {
            if (receiptOrderId.startsWith("APP_")) {
                Long applicationId = Long.parseLong(receiptOrderId.split("_")[1]);
                Application app    = applicationRepository.findById(applicationId).orElseThrow();
                String applicantNo = app.getApplicant().getApplicantNo();

                BigDecimal amountPaid = paymentRepository.findByOrderId(receiptOrderId)
                        .map(p -> p.getAmount() != null ? BigDecimal.valueOf(p.getAmount()) : null)
                        .orElse(null);

                applicationService.confirmPayment(applicationId, applicantNo, amountPaid);

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

                if (receiptOrderId.startsWith("SLIDEUP_")) {
                    counselingService.slideUpAllotment(applicantNo, allotmentId);
                    logger.info("Slide Up fee paid for allotment {} by applicant {} — status set to SLIDE_UP", allotmentId, applicantNo);
                } else {
                    // Status flips PENDING -> ACCEPTED here, only now that payment has actually
                    // succeeded. The allotment was still PENDING right up until this point
                    // (initiate-seat-fee requires PENDING), so this is the real, final
                    // acceptance of the seat, triggered by confirmed payment — not before it.
                    counselingService.acceptAllotment(applicantNo, allotmentId);
                    logger.info("Seat acceptance fee paid, allotment {} accepted for applicant {}", allotmentId, applicantNo);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to finalise post-payment logic for order {}", receiptOrderId, e);
        }
    }
}