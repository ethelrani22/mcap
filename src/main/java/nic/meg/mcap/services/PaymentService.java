package nic.meg.mcap.services;

import java.util.Map;

import nic.meg.mcap.entities.Payment;

public interface PaymentService {

    Map<String, String> createOrder(Double amount, String customerId, String customerName,
                                    String customerEmail, String customerPhone,
                                    String returnUrl, String orderId);

    /** Fetches Razorpay order-level status (created / attempted / paid). */
    Map<String, Object> fetchPaymentStatus(String razorpayOrderId);

    /**
     * Fetches Razorpay payment-level details for a given pay_XXXXXXXX ID.
     * Returns method, vpa, bank, acquirer_data (rrn, upi_transaction_id), etc.
     */
    Map<String, Object> fetchPaymentDetails(String razorpayPaymentId);

    boolean verifyPaymentSignature(String razorpayOrderId,
                                   String razorpayPaymentId,
                                   String razorpaySignature);

    void updatePaymentStatus(String orderId, String status);

    /**
     * Persists the Razorpay payment ID and signature onto the Payment record
     * after a successful callback, then enriches with bank-level details
     * (RRN, UPI transaction ID, VPA, bank, payment method).
     */
    void finalizePaymentRecord(String orderId, String razorpayPaymentId, String razorpaySignature);

    /**
     * Extracts and saves bank-level fields (method, vpa, bank, acquirer_data)
     * from a Razorpay payment entity map onto the given Payment object.
     * Does NOT call save() — caller is responsible for persisting.
     */
    void enrichPaymentWithBankDetails(Payment payment, Map<String, Object> razorpayPaymentEntity);
}
