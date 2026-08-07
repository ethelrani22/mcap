package nic.meg.mcap.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import nic.meg.mcap.entities.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(String orderId);
    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    // Used by PaymentBackfillRunner to find all payments not yet enriched with bank details
    List<Payment> findByBankRrnIsNullAndRazorpayPaymentIdIsNotNull();

    List<Payment> findByStatus(String status);

    Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);

    // Used by PaymentBackfillRunner to find payments stuck in a non-final state
    // that may have actually succeeded/failed on Razorpay's side without our DB knowing.
    List<Payment> findByStatusInAndRazorpayOrderIdIsNotNull(List<String> statuses);

    // FIX 4a: Check if a successful payment already exists for a given applicationId.
    // Used by PaymentController.initiateApplicationFee() to block duplicate payment initiation.
    // The orderId for application payments follows the pattern: APP_{applicationId}_{timestamp}
    //
    // WHY NOT LIKE: LIKE CONCAT('APP_', :id, '_%') is BROKEN — '_' is a SQL single-char
    // Using LOCATE for an exact prefix match instead — no wildcards, no ambiguity.
    @Query("SELECT COUNT(p) > 0 FROM Payment p " +
            "WHERE LOCATE(CONCAT('APP_', CAST(:applicationId AS string), '_'), p.orderId) = 1 " +
            "AND p.status = :status")
    boolean existsByApplicationIdAndStatus(@Param("applicationId") Long applicationId,
                                           @Param("status") String status);

    // FIX 4b: Check if a successful payment already exists for a given allotmentId.
    // Used by PaymentController.initiateSeatFee() to block duplicate seat fee payment initiation.
    // The orderId for seat payments follows: SEAT_{allotmentId}_{timestamp} or SLIDEUP_{allotmentId}_{timestamp}
    //
    // Same LIKE wildcard bug fixed here — '_' in SEAT_ and SLIDEUP_ were wildcards without escaping.
    @Query("SELECT COUNT(p) > 0 FROM Payment p " +
            "WHERE (LOCATE(CONCAT('SEAT_', CAST(:allotmentId AS string), '_'), p.orderId) = 1 " +
            "    OR LOCATE(CONCAT('SLIDEUP_', CAST(:allotmentId AS string), '_'), p.orderId) = 1) " +
            "AND p.status = :status")
    boolean existsByAllotmentIdAndStatus(@Param("allotmentId") Long allotmentId,
                                         @Param("status") String status);

    // Checks specifically whether the seat ACCEPTANCE/ADMISSION fee (SEAT_ prefix) has been
    // paid for this allotment — deliberately excludes SLIDEUP_ orders, since slide-up fee
    // and admission fee are different charges. Used to lock subject preferences once the
    // admission fee is actually paid (not merely once the seat is ACCEPTED).
    @Query("SELECT COUNT(p) > 0 FROM Payment p " +
            "WHERE LOCATE(CONCAT('SEAT_', CAST(:allotmentId AS string), '_'), p.orderId) = 1 " +
            "AND p.status = :status")
    boolean existsAdmissionFeePaymentByAllotmentIdAndStatus(@Param("allotmentId") Long allotmentId,
                                                            @Param("status") String status);

    // Fetches the actual successful admission-fee Payment record (SEAT_ prefix only) for a
    // given allotment, so the seat-fee receipt can show real transaction details (amount,
    // date, gateway reference). Used by generateSeatFeeReceiptPdf().
    @Query("SELECT p FROM Payment p " +
            "WHERE LOCATE(CONCAT('SEAT_', CAST(:allotmentId AS string), '_'), p.orderId) = 1 " +
            "AND p.status = :status")
    Optional<Payment> findAdmissionFeePaymentByAllotmentIdAndStatus(@Param("allotmentId") Long allotmentId,
                                                                    @Param("status") String status);

    // Slide-up fee (flat, one-time per applicant per admission cycle — not per allotment).
    // customerId = applicant.applicantNo (set in PaymentServiceImpl.createOrder). Checked
    // across ALL of the applicant's allotments/orderIds, not just the current one, so that
    // sliding up again in a later round never re-charges the flat fee.
    @Query("SELECT COUNT(p) > 0 FROM Payment p " +
            "WHERE p.customerId = :applicantNo " +
            "AND LOCATE('SLIDEUP_', p.orderId) = 1 " +
            "AND p.status = :status")
    boolean existsSuccessfulSlideUpPaymentForApplicant(@Param("applicantNo") String applicantNo,
                                                       @Param("status") String status);

    // Finds unresolved (CREATED — order placed with Razorpay, but not yet confirmed
    // successful or failed) payments for this allotment, matching either SEAT_ or
    // SLIDEUP_ prefix, most recent first. Used to detect "a payment is already in
    // progress" and avoid creating a second concurrent Razorpay order for the same
    // allotment (e.g. after a connection drop mid-checkout).
    @Query("SELECT p FROM Payment p " +
            "WHERE (LOCATE(CONCAT('SEAT_', CAST(:allotmentId AS string), '_'), p.orderId) = 1 " +
            "    OR LOCATE(CONCAT('SLIDEUP_', CAST(:allotmentId AS string), '_'), p.orderId) = 1) " +
            "AND p.status = 'CREATED' " +
            "ORDER BY p.createdAt DESC")
    List<Payment> findInProgressPaymentsForAllotment(@Param("allotmentId") Long allotmentId);
}