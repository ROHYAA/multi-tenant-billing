package com.mtbs.billing.repository;

import com.mtbs.billing.entity.Invoice;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByStatus(InvoiceStatus status);

    Page<Invoice> findBySubscriptionId(Long subscriptionId, Pageable pageable);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    List<Invoice> findAllByStatusAndDueDateBefore(InvoiceStatus status, Instant date);

    long countByStatus(InvoiceStatus status);

    Optional<Invoice> findTopBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId);

    List<Invoice> findBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId);
}
