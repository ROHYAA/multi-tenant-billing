package com.mtbs.business.invoice.repository;

import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.business.invoice.entity.BusinessInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface BusinessInvoiceItemRepository extends JpaRepository<BusinessInvoiceItem, Long> {
    List<BusinessInvoiceItem> findAllByInvoice(BusinessInvoice invoice);

    List<BusinessInvoiceItem> findAllByInvoiceId(Long invoiceId);

    void deleteByInvoiceId(Long invoiceId);

    long countByInvoiceId(Long invoiceId);

    @Query("SELECT COALESCE(SUM(i.total), 0) FROM BusinessInvoiceItem i WHERE i.invoice.id = :invoiceId")
    BigDecimal sumTotalByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("SELECT COALESCE(SUM(i.taxAmount), 0) FROM BusinessInvoiceItem i WHERE i.invoice.id = :invoiceId")
    BigDecimal sumTaxByInvoiceId(@Param("invoiceId") Long invoiceId);
}