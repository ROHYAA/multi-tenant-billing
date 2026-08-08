package com.mtbs.business.invoice.repository;

import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface BillItemRepository extends JpaRepository<BillItem, Long> {
    List<BillItem> findAllByInvoice(Bill invoice);

    List<BillItem> findAllByInvoiceId(Long invoiceId);

    void deleteByInvoiceId(Long invoiceId);

    long countByInvoiceId(Long invoiceId);

    @Query("SELECT COALESCE(SUM(i.total), 0) FROM BillItem i WHERE i.invoice.id = :invoiceId")
    BigDecimal sumTotalByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("SELECT COALESCE(SUM(i.taxAmount), 0) FROM BillItem i WHERE i.invoice.id = :invoiceId")
    BigDecimal sumTaxByInvoiceId(@Param("invoiceId") Long invoiceId);
}