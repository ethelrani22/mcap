package nic.meg.mcap.services.impl;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import nic.meg.mcap.entities.Payment;
import nic.meg.mcap.repositories.PaymentRepository;
import nic.meg.mcap.services.PaymentService;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final String ORDERS_URL   = "https://api.razorpay.com/v1/orders";
    private static final String PAYMENTS_URL = "https://api.razorpay.com/v1/payments";

    @Value("${razorpay.key.id}")
    private String keyId;

    @Value("${razorpay.key.secret}")
    private String keySecret;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    // ─────────────────────────────────────────────────────────────────────
    // 1. CREATE ORDER — called before showing the Razorpay checkout modal
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public Map<String, String> createOrder(Double amount, String customerId, String customerName,
                                           String customerEmail, String customerPhone,
                                           String returnUrl, String orderId) {

        // Razorpay requires amount in PAISE (multiply by 100, no decimals)
        int amountInPaise = (int) Math.round(amount * 100);

        Map<String, Object> request = Map.of(
                "amount",   amountInPaise,
                "currency", "INR",
                "receipt",  orderId,
                "notes", Map.of(
                        "customer_id",    customerId,
                        "customer_name",  customerName,
                        "customer_email", customerEmail,
                        "receipt",        orderId
                )
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, getHeaders());
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(ORDERS_URL, HttpMethod.POST, entity,
                        new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("id")) {
            throw new RuntimeException("Invalid response from Razorpay: " + body);
        }

        String razorpayOrderId = (String) body.get("id");

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setRazorpayOrderId(razorpayOrderId);
        payment.setAmount(amount);
        payment.setCurrency("INR");
        payment.setCustomerId(customerId);
        payment.setStatus("CREATED");
        payment.setCreatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        return Map.of(
                "orderId",   razorpayOrderId,
                "keyId",     keyId,
                "receiptId", orderId
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. FETCH PAYMENT STATUS (order-level)
    //    Razorpay order statuses: "created" | "attempted" | "paid"
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public Map<String, Object> fetchPaymentStatus(String razorpayOrderId) {
        String url = ORDERS_URL + "/" + razorpayOrderId;
        HttpEntity<Void> entity = new HttpEntity<>(getHeaders());

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(url, HttpMethod.GET, entity,
                        new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, Object> body = response.getBody();

        if (body != null && body.containsKey("status")) {
            String rzpStatus = (String) body.get("status");
            String internalStatus = switch (rzpStatus) {
                case "paid"      -> "PAYMENT_SUCCESS";
                case "attempted" -> "PAYMENT_ATTEMPTED";
                default          -> rzpStatus.toUpperCase();
            };
            paymentRepository.findByRazorpayOrderId(razorpayOrderId)
                    .ifPresent(p -> updatePaymentStatus(p.getOrderId(), internalStatus));
        }

        return body;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. VERIFY PAYMENT SIGNATURE
    //    HMAC-SHA256(keySecret, razorpayOrderId + "|" + razorpayPaymentId)
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public boolean verifyPaymentSignature(String razorpayOrderId,
                                          String razorpayPaymentId,
                                          String razorpaySignature) {
        try {
            String message = razorpayOrderId + "|" + razorpayPaymentId;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    keySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) hexString.append(String.format("%02x", b));
            return hexString.toString().equals(razorpaySignature);
        } catch (Exception e) {
            throw new RuntimeException("Signature verification failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. UPDATE PAYMENT STATUS — gateway-agnostic
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public void updatePaymentStatus(String orderId, String status) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        payment.setStatus(status);
        payment.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. FINALIZE PAYMENT RECORD
    //    Persists razorpayPaymentId + signature, then fetches bank-level
    //    details (RRN, UPI txn ID, VPA, bank, method) from Razorpay.
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public void finalizePaymentRecord(String orderId, String razorpayPaymentId, String razorpaySignature) {
        paymentRepository.findByOrderId(orderId).ifPresent(p -> {
            p.setRazorpayPaymentId(razorpayPaymentId);
            p.setRazorpaySignature(razorpaySignature);
            p.setUpdatedAt(LocalDateTime.now());

            try {
                Map<String, Object> details = fetchPaymentDetails(razorpayPaymentId);
                enrichPaymentWithBankDetails(p, details);
            } catch (Exception e) {
                // Non-fatal — bank details can be backfilled later
            }

            paymentRepository.save(p);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. FETCH PAYMENT DETAILS (payment-level)
    //    GET /v1/payments/{razorpayPaymentId}
    //    Returns method, vpa, bank, acquirer_data (rrn, upi_transaction_id)
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public Map<String, Object> fetchPaymentDetails(String razorpayPaymentId) {
        String url = PAYMENTS_URL + "/" + razorpayPaymentId;
        HttpEntity<Void> entity = new HttpEntity<>(getHeaders());
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(url, HttpMethod.GET, entity,
                        new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        return response.getBody();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7. ENRICH PAYMENT WITH BANK DETAILS
    //    Shared helper used by both the frontend callback path
    //    and the webhook path (webhook has acquirer_data inline).
    // ─────────────────────────────────────────────────────────────────────
    @Override
    @SuppressWarnings("unchecked")
    public void enrichPaymentWithBankDetails(Payment payment, Map<String, Object> razorpayPaymentEntity) {
        if (razorpayPaymentEntity == null) return;

        String method = (String) razorpayPaymentEntity.get("method");
        if (method != null) payment.setPaymentMethod(method);

        String vpa = (String) razorpayPaymentEntity.get("vpa");
        if (vpa != null) payment.setVpa(vpa);

        String bank = (String) razorpayPaymentEntity.get("bank");
        if (bank != null) payment.setBank(bank);

        Object acqRaw = razorpayPaymentEntity.get("acquirer_data");
        if (acqRaw instanceof Map<?, ?> acqMap) {
            Map<String, Object> acq = (Map<String, Object>) acqMap;

            String rrn = (String) acq.get("rrn");
            if (rrn != null) payment.setBankRrn(rrn);

            String upiTxnId = (String) acq.get("upi_transaction_id");
            if (upiTxnId != null) payment.setUpiTransactionId(upiTxnId);

            String bankTxnId = (String) acq.get("bank_transaction_id");
            if (bankTxnId != null && payment.getBankRrn() == null) {
                payment.setBankRrn(bankTxnId);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HEADERS — Basic Auth
    // ─────────────────────────────────────────────────────────────────────
    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(keyId, keySecret);
        return headers;
    }
}
