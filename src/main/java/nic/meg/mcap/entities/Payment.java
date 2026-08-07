package nic.meg.mcap.entities;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Your internal order reference (used as Razorpay receipt)
    private String orderId;

    // Razorpay-specific fields
    @Column(name = "razorpay_order_id")
    private String razorpayOrderId;         // e.g. order_XXXXXXXXXXXXXXXX

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;       // e.g. pay_XXXXXXXXXXXXXXXX (set after capture)

    @Column(name = "razorpay_signature")
    private String razorpaySignature;       // HMAC-SHA256 signature (set after payment success)

    private Double amount;
    private String currency;
    private String customerId;
    private String status;                  // CREATED, PAYMENT_SUCCESS, PAYMENT_ATTEMPTED, FAILED

    // ── Bank / gateway level fields (populated after payment capture) ──────────

    /** Payment method: upi | card | netbanking | wallet | emi */
    @Column(name = "payment_method")
    private String paymentMethod;

    /**
     * Bank RRN (Reference Reference Number) — the bank's unique transaction reference.
     * Present for UPI and netbanking payments (acquirer_data.rrn).
     * This is what applicants see on their bank statement.
     */
    @Column(name = "bank_rrn")
    private String bankRrn;

    /**
     * UPI Transaction ID — the transaction ID visible in the payer's UPI app
     * (acquirer_data.upi_transaction_id). Present only for UPI payments.
     */
    @Column(name = "upi_transaction_id")
    private String upiTransactionId;

    /**
     * UPI VPA (Virtual Payment Address) of the payer — e.g. 9876543210@upi.
     * Present only for UPI payments.
     */
    @Column(name = "vpa")
    private String vpa;

    /**
     * Bank code for netbanking payments — e.g. HDFC, SBI, ICICI.
     * Present only for netbanking and some card payments.
     */
    @Column(name = "bank")
    private String bank;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
