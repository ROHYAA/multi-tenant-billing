package com.mtbs.billing.repository;

import com.mtbs.billing.entity.Payment;
import com.mtbs.shared.enums.billing.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String key);

    Optional<Payment> findByRazorpayOrderId(String orderId);

    Optional<Payment> findByRazorpayPaymentId(String paymentId);

    List<Payment> findAllByStatusAndNextRetryAtBefore(PaymentStatus status, Instant now);

    List<Payment> findByInvoiceId(Long invoiceId);

    long countByStatus(PaymentStatus paymentStatus);

    long countByInvoiceId(Long invoiceId);

    long countByInvoiceIdAndStatus(Long invoiceId, PaymentStatus status);

    long countByInvoiceIdIn(List<Long> invoiceIds);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.invoiceId IN " +
           "(SELECT i.id FROM Invoice i WHERE i.subscriptionId = :subscriptionId) AND p.status = :status")
    BigDecimal sumAmountBySubscriptionIdAndStatus(Long subscriptionId, PaymentStatus status);

    Optional<Payment> findTopByInvoiceIdInOrderByPaidAtDesc(List<Long> invoiceIds);

    @Query("SELECT AVG(p.amount) FROM Payment p WHERE p.invoiceId IN " +
           "(SELECT i.id FROM Invoice i WHERE i.subscriptionId = :subscriptionId) AND p.status = :status")
    BigDecimal avgAmountBySubscriptionIdAndStatus(Long subscriptionId, PaymentStatus status);

    Optional<Payment> findTopByInvoiceIdInOrderByCreatedAtDesc(List<Long> invoiceIds);
}
