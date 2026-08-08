package com.mtbs.business.report.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MonthlyReportRow {
 
    private int    year;
    private int    month;          // 1-12
    private String monthName;      // "Jan", "Feb", etc.
 
    /** Count of invoices created in this month (all statuses). */
    private int    invoiceCount;
 
    /** Sum of totalAmount for invoices created in this month. */
    private BigDecimal invoiceTotal;
 
    /** Sum of business_payments.amount where paid_at falls in this month. */
    private BigDecimal collected;
 
    /** invoiceTotal - collected (floored at zero). */
    private BigDecimal outstanding;
}