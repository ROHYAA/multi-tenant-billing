package com.mtbs.business.report.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutstandingReportResponse {
 
    /** Sum of totalAmount across all OPEN invoices. */
    private BigDecimal totalOutstanding;
 
    /** Sum of totalAmount for overdue invoices (past due date). */
    private BigDecimal overdueAmount;
 
    /** Count of overdue invoices. */
    private int overdueCount;
 
    /** Sum of totalAmount for current invoices (not yet due). */
    private BigDecimal currentAmount;
 
    /** Count of current invoices. */
    private int currentCount;
 
    /** Individual invoice detail sorted by due date ascending (oldest first). */
    private List<OutstandingItem> items;
 
    // ── Nested DTO ────────────────────────────────────────────────────────────
 
    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OutstandingItem {
        private Long       invoiceId;
        private String     invoiceNumber;
        private Long       customerId;
        private BigDecimal totalAmount;
 
        /** invoice.totalAmount - sum(business_payments.amount). */
        private BigDecimal outstandingAmount;
 
        /** Null if invoice was finalized without a due date (shouldn't happen normally). */
        private Instant dueDate;
 
        /** True when dueDate is in the past. */
        private boolean overdue;
    }
}