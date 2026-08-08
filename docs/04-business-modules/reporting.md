# Reporting & Analytics Module

## Overview

The **Reporting & Analytics** module provides business intelligence dashboards and reports for tenant billing data. It enables financial analysis, revenue tracking, outstanding invoices management, and monthly financial summaries.

**Module Location:** `src/main/java/com/mtbs/business/report/`

---

## Report Types

```
┌─────────────────────────────────────────────┐
│      REPORTING & ANALYTICS SYSTEM          │
├─────────────────────────────────────────────┤
│                                             │
│  1. Revenue Report                          │
│     └─ Time-period analysis                 │
│     └─ Payment method breakdown             │
│     └─ Aggregated metrics                   │
│                                             │
│  2. Outstanding Report                      │
│     └─ Current open invoices                │
│     └─ Overdue invoices                     │
│     └─ Outstanding balance per invoice      │
│                                             │
│  3. Monthly Summary                         │
│     └─ Year view (12 months)                │
│     └─ Invoice & payment trends             │
│     └─ Month-over-month comparison          │
│                                             │
└─────────────────────────────────────────────┘
```

---

## Report 1: Revenue Report

### Purpose

Analyze revenue over a specified time period with payment method breakdown.

### API Endpoint

```http
GET /api/v*/reports/revenue?from=2026-05-01T00:00:00Z&to=2026-05-31T23:59:59Z

Authorization: Bearer <token>
```

**Query Parameters:**
- `from` — Start date (ISO-8601 Instant, e.g., 2026-05-01T00:00:00Z)
- `to` — End date (ISO-8601 Instant, e.g., 2026-05-31T23:59:59Z)

### Response

```json
{
  "period": {
    "from": "2026-05-01T00:00:00Z",
    "to": "2026-05-31T23:59:59Z"
  },
  "summary": {
    "totalRevenue": "15750.50",
    "totalInvoicesPaid": 21,
    "averageInvoiceValue": "750.50"
  },
  "byPaymentMethod": [
    {
      "method": "CARD",
      "amount": "8500.00",
      "invoiceCount": 12,
      "percentage": 53.96
    },
    {
      "method": "UPI",
      "amount": "4200.00",
      "invoiceCount": 8,
      "percentage": 26.67
    },
    {
      "method": "NETBANKING",
      "amount": "2050.50",
      "invoiceCount": 1,
      "percentage": 13.01
    },
    {
      "method": "BANK_TRANSFER",
      "amount": "1000.00",
      "invoiceCount": 1,
      "percentage": 6.35
    }
  ]
}
```

### Calculation Details

```
totalRevenue = SUM(payment.amount) for all payments in period where payment.paidAt IN [from, to]
totalInvoicesPaid = COUNT(DISTINCT invoice.id) where invoice.status = PAID and payment.paidAt IN [from, to]
averageInvoiceValue = totalRevenue / totalInvoicesPaid
byPaymentMethod = GROUP BY payment.method, SUM(amount), COUNT(*)
percentage = (method_amount / totalRevenue) * 100
```

### Use Cases

✅ **Monthly Revenue Report** — Executive summary of sales  
✅ **Payment Method Analysis** — Which channels generate most revenue?  
✅ **Cash Flow Analysis** — When did payments arrive?  
✅ **Year-End Reconciliation** — Full year revenue breakdown  
✅ **Accounting Integration** — Export to accounting software  

---

## Report 2: Outstanding Report

### Purpose

Track unpaid invoices and outstanding balances by customer.

### API Endpoint

```http
GET /api/v*/reports/outstanding

Authorization: Bearer <token>
```

**Query Parameters:** None (always shows current state)

### Response

```json
{
  "overdue": [
    {
      "invoiceNumber": "BINV-12345-202604-001",
      "customerId": 5,
      "customerName": "Acme Corp",
      "totalAmount": "5000.00",
      "paidAmount": "2500.00",
      "outstandingBalance": "2500.00",
      "dueDate": "2026-04-30",
      "daysOverdue": 26,
      "status": "OPEN"
    },
    {
      "invoiceNumber": "BINV-12345-202604-002",
      "customerId": 7,
      "customerName": "Beta Inc",
      "totalAmount": "3000.00",
      "paidAmount": "0.00",
      "outstandingBalance": "3000.00",
      "dueDate": "2026-04-15",
      "daysOverdue": 41,
      "status": "OPEN"
    }
  ],
  "current": [
    {
      "invoiceNumber": "BINV-12345-202605-003",
      "customerId": 8,
      "customerName": "Gamma LLC",
      "totalAmount": "7500.00",
      "paidAmount": "0.00",
      "outstandingBalance": "7500.00",
      "dueDate": "2026-06-15",
      "daysUntilDue": 20,
      "status": "OPEN"
    }
  ],
  "summary": {
    "totalOverdueAmount": "5500.00",
    "overdueInvoiceCount": 2,
    "totalCurrentAmount": "7500.00",
    "currentInvoiceCount": 1,
    "totalOutstanding": "13000.00"
  }
}
```

### Calculation Details

```
Overdue = invoices where status = OPEN AND dueDate < today()
Current = invoices where status = OPEN AND dueDate >= today()

For each invoice:
  outstandingBalance = totalAmount - SUM(payment.amount for all payments)
  daysOverdue = today() - dueDate (if overdue)
  daysUntilDue = dueDate - today() (if current)
```

### Use Cases

✅ **Collection Management** — Identify priority collection targets  
✅ **Cash Flow Forecast** — Predict incoming cash based on due dates  
✅ **Customer Credit Risk** — Track customers with consistent delays  
✅ **Dunning Management** — Send payment reminders to customers  
✅ **Financial Reporting** — Accounts receivable aging  

---

## Report 3: Monthly Summary

### Purpose

Year-over-year monthly trends for invoicing, payments, and outstanding balance.

### API Endpoint

```http
GET /api/v*/reports/monthly?year=2026

Authorization: Bearer <token>
```

**Query Parameters:**
- `year` — Calendar year (default: current year)
- Optional: Returns 12 months (Jan-Dec) for the year

### Response

```json
{
  "year": 2026,
  "currency": "INR",
  "months": [
    {
      "month": "JANUARY",
      "monthNumber": 1,
      "invoicesCreated": 15,
      "invoiceAmountTotal": "45000.00",
      "amountCollected": "42000.00",
      "amountOutstanding": "3000.00",
      "paymentCount": 18,
      "collectionRate": 93.33
    },
    {
      "month": "FEBRUARY",
      "monthNumber": 2,
      "invoicesCreated": 18,
      "invoiceAmountTotal": "52500.00",
      "amountCollected": "48000.00",
      "amountOutstanding": "4500.00",
      "paymentCount": 21,
      "collectionRate": 91.43
    },
    {
      "month": "MARCH",
      "monthNumber": 3,
      "invoicesCreated": 21,
      "invoiceAmountTotal": "63000.00",
      "amountCollected": "60500.00",
      "amountOutstanding": "2500.00",
      "paymentCount": 24,
      "collectionRate": 95.97
    },
    {
      "month": "APRIL",
      "monthNumber": 4,
      "invoicesCreated": 19,
      "invoiceAmountTotal": "57500.00",
      "amountCollected": "55000.00",
      "amountOutstanding": "2500.00",
      "paymentCount": 22,
      "collectionRate": 95.65
    },
    {
      "month": "MAY",
      "monthNumber": 5,
      "invoicesCreated": 23,
      "invoiceAmountTotal": "70000.00",
      "amountCollected": "65000.00",
      "amountOutstanding": "5000.00",
      "paymentCount": 28,
      "collectionRate": 92.86
    }
  ],
  "ytdSummary": {
    "totalInvoicesCreated": 96,
    "totalInvoiceAmount": "288000.00",
    "totalCollected": "270500.00",
    "totalOutstanding": "17500.00",
    "averageCollectionRate": 93.92
  }
}
```

### Calculation Details

```
For each month:
  invoicesCreated = COUNT(invoice) where MONTH(createdAt) = month AND status IN [DRAFT, OPEN, PAID, VOID]
  invoiceAmountTotal = SUM(totalAmount) for all invoices in month
  amountCollected = SUM(payment.amount) where payment.paidAt IN [month start, month end]
  amountOutstanding = SUM(totalAmount - SUM(payments)) for OPEN invoices in month
  paymentCount = COUNT(payment) where paidAt IN [month start, month end]
  collectionRate = (amountCollected / invoiceAmountTotal) * 100

YTD Summary = Aggregate all months for the year
```

### Use Cases

✅ **Revenue Trending** — Month-over-month revenue growth  
✅ **Seasonality Analysis** — Which months are strong/weak?  
✅ **Collection Performance** — Track collection rate trends  
✅ **Budget Planning** — Compare actuals vs forecast  
✅ **Investor Reporting** — Show growth trajectory  
✅ **Tax Planning** — Monthly breakdown for Q-tax filings  

---

## Service Implementation

### BusinessReportService

```java
@Service
@RequiredArgsConstructor
public class BusinessReportService {
    
    private final BusinessPaymentRepository paymentRepository;
    private final BusinessInvoiceRepository invoiceRepository;
    
    /**
     * Revenue report for time period with payment method breakdown.
     */
    public RevenueReportDTO getRevenueReport(Instant from, Instant to) {
        // Validate date range
        if (from.isAfter(to)) {
            throw ResourceException.invalid("'from' date must be before 'to' date");
        }
        
        // Query payments in period
        List<BusinessPayment> payments = paymentRepository.findByPaidAtBetween(from, to);
        
        if (payments.isEmpty()) {
            return RevenueReportDTO.empty(from, to);
        }
        
        // Calculate summary metrics
        BigDecimal totalRevenue = payments.stream()
            .map(BusinessPayment::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        Set<Long> paidInvoiceIds = payments.stream()
            .map(BusinessPayment::getInvoiceId)
            .collect(Collectors.toSet());
        
        int invoicePaidCount = paidInvoiceIds.size();
        BigDecimal avgInvoiceValue = invoicePaidCount > 0 
            ? totalRevenue.divide(BigDecimal.valueOf(invoicePaidCount), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        // Group by payment method
        Map<PaymentMethod, List<BusinessPayment>> byMethod = payments.stream()
            .collect(Collectors.groupingBy(BusinessPayment::getMethod));
        
        List<PaymentMethodBreakdown> methodBreakdowns = byMethod.entrySet().stream()
            .map(entry -> {
                PaymentMethod method = entry.getKey();
                List<BusinessPayment> methodPayments = entry.getValue();
                
                BigDecimal methodAmount = methodPayments.stream()
                    .map(BusinessPayment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                BigDecimal percentage = totalRevenue.compareTo(BigDecimal.ZERO) > 0
                    ? methodAmount.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;
                
                return PaymentMethodBreakdown.builder()
                    .method(method)
                    .amount(methodAmount)
                    .invoiceCount(methodPayments.size())
                    .percentage(percentage)
                    .build();
            })
            .sorted(Comparator.comparing(PaymentMethodBreakdown::getAmount).reversed())
            .collect(Collectors.toList());
        
        return RevenueReportDTO.builder()
            .period(PeriodDTO.of(from, to))
            .summary(RevenueReportDTO.Summary.builder()
                .totalRevenue(totalRevenue)
                .totalInvoicesPaid(invoicePaidCount)
                .averageInvoiceValue(avgInvoiceValue)
                .build())
            .byPaymentMethod(methodBreakdowns)
            .build();
    }
    
    /**
     * Outstanding invoices report (overdue + current).
     */
    public OutstandingReportDTO getOutstandingReport() {
        List<BusinessInvoice> openInvoices = invoiceRepository.findByStatus(InvoiceStatus.OPEN);
        
        List<OutstandingInvoiceDTO> overdue = new ArrayList<>();
        List<OutstandingInvoiceDTO> current = new ArrayList<>();
        
        LocalDate today = LocalDate.now();
        BigDecimal totalOverdueAmount = BigDecimal.ZERO;
        BigDecimal totalCurrentAmount = BigDecimal.ZERO;
        
        for (BusinessInvoice invoice : openInvoices) {
            // Calculate outstanding balance
            BigDecimal paidAmount = paymentRepository.findByInvoiceId(invoice.getId()).stream()
                .map(BusinessPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal outstanding = invoice.getTotalAmount().subtract(paidAmount);
            
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // Skip fully paid (shouldn't happen but be safe)
            }
            
            OutstandingInvoiceDTO invoiceDTO = OutstandingInvoiceDTO.builder()
                .invoiceNumber(invoice.getInvoiceNumber())
                .customerId(invoice.getCustomerId())
                .customerName(invoice.getCustomer().getName()) // ⚠️ Assumes eager loaded
                .totalAmount(invoice.getTotalAmount())
                .paidAmount(paidAmount)
                .outstandingBalance(outstanding)
                .dueDate(invoice.getDueDate())
                .status(invoice.getStatus())
                .build();
            
            if (invoice.getDueDate().isBefore(today)) {
                long daysOverdue = ChronoUnit.DAYS.between(invoice.getDueDate(), today);
                invoiceDTO.setDaysOverdue((int) daysOverdue);
                overdue.add(invoiceDTO);
                totalOverdueAmount = totalOverdueAmount.add(outstanding);
            } else {
                long daysUntilDue = ChronoUnit.DAYS.between(today, invoice.getDueDate());
                invoiceDTO.setDaysUntilDue((int) daysUntilDue);
                current.add(invoiceDTO);
                totalCurrentAmount = totalCurrentAmount.add(outstanding);
            }
        }
        
        BigDecimal totalOutstanding = totalOverdueAmount.add(totalCurrentAmount);
        
        return OutstandingReportDTO.builder()
            .overdue(overdue)
            .current(current)
            .summary(OutstandingReportDTO.Summary.builder()
                .totalOverdueAmount(totalOverdueAmount)
                .overdueInvoiceCount(overdue.size())
                .totalCurrentAmount(totalCurrentAmount)
                .currentInvoiceCount(current.size())
                .totalOutstanding(totalOutstanding)
                .build())
            .build();
    }
    
    /**
     * Monthly summary report for a calendar year.
     */
    public MonthlySummaryReportDTO getMonthlySummary(int year) {
        List<MonthlySummaryDTO> months = new ArrayList<>();
        
        LocalDateTime yearStart = LocalDateTime.of(year, Month.JANUARY, 1, 0, 0, 0);
        LocalDateTime yearEnd = LocalDateTime.of(year, Month.DECEMBER, 31, 23, 59, 59);
        
        BigDecimal totalInvoiceAmount = BigDecimal.ZERO;
        BigDecimal totalCollected = BigDecimal.ZERO;
        int totalInvoicesCreated = 0;
        
        for (Month month : Month.values()) {
            LocalDateTime monthStart = LocalDateTime.of(year, month, 1, 0, 0, 0);
            LocalDateTime monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth())
                .withHour(23).withMinute(59).withSecond(59);
            
            // Count invoices created in month
            List<BusinessInvoice> invoicesInMonth = invoiceRepository
                .findByCreatedAtBetween(monthStart, monthEnd);
            
            BigDecimal monthInvoiceAmount = invoicesInMonth.stream()
                .map(BusinessInvoice::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // Sum payments collected in month
            List<BusinessPayment> paymentsInMonth = paymentRepository
                .findByPaidAtBetween(
                    monthStart.toInstant(ZoneOffset.UTC),
                    monthEnd.toInstant(ZoneOffset.UTC)
                );
            
            BigDecimal monthCollected = paymentsInMonth.stream()
                .map(BusinessPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // Calculate outstanding (open invoices in month)
            BigDecimal monthOutstanding = invoicesInMonth.stream()
                .filter(inv -> inv.getStatus() == InvoiceStatus.OPEN)
                .map(inv -> {
                    BigDecimal paid = paymentsInMonth.stream()
                        .filter(p -> p.getInvoiceId().equals(inv.getId()))
                        .map(BusinessPayment::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return inv.getTotalAmount().subtract(paid);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal collectionRate = monthInvoiceAmount.compareTo(BigDecimal.ZERO) > 0
                ? monthCollected.divide(monthInvoiceAmount, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
            
            MonthlySummaryDTO monthDTO = MonthlySummaryDTO.builder()
                .month(month)
                .monthNumber(month.getValue())
                .invoicesCreated(invoicesInMonth.size())
                .invoiceAmountTotal(monthInvoiceAmount)
                .amountCollected(monthCollected)
                .amountOutstanding(monthOutstanding)
                .paymentCount(paymentsInMonth.size())
                .collectionRate(collectionRate)
                .build();
            
            months.add(monthDTO);
            
            totalInvoicesCreated += invoicesInMonth.size();
            totalInvoiceAmount = totalInvoiceAmount.add(monthInvoiceAmount);
            totalCollected = totalCollected.add(monthCollected);
        }
        
        BigDecimal totalOutstanding = totalInvoiceAmount.subtract(totalCollected);
        BigDecimal avgCollectionRate = totalInvoiceAmount.compareTo(BigDecimal.ZERO) > 0
            ? totalCollected.divide(totalInvoiceAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        
        return MonthlySummaryReportDTO.builder()
            .year(year)
            .currency("INR")
            .months(months)
            .ytdSummary(YTDSummaryDTO.builder()
                .totalInvoicesCreated(totalInvoicesCreated)
                .totalInvoiceAmount(totalInvoiceAmount)
                .totalCollected(totalCollected)
                .totalOutstanding(totalOutstanding)
                .averageCollectionRate(avgCollectionRate)
                .build())
            .build();
    }
}
```

---

## Controller Implementation

### BusinessReportController

```java
@RestController
@RequestMapping("/api/v*/reports")
@RequiredArgsConstructor
@Secured("PERMISSION_BILLING_MANAGE")
public class BusinessReportController {
    
    private final BusinessReportService reportService;
    
    @GetMapping("/revenue")
    public ResponseEntity<RevenueReportDTO> getRevenueReport(
        @RequestParam Instant from,
        @RequestParam Instant to
    ) {
        RevenueReportDTO report = reportService.getRevenueReport(from, to);
        return ResponseEntity.ok(report);
    }
    
    @GetMapping("/outstanding")
    public ResponseEntity<OutstandingReportDTO> getOutstandingReport() {
        OutstandingReportDTO report = reportService.getOutstandingReport();
        return ResponseEntity.ok(report);
    }
    
    @GetMapping("/monthly")
    public ResponseEntity<MonthlySummaryReportDTO> getMonthlySummary(
        @RequestParam(defaultValue = "#{T(java.time.Year).now().getValue()}") int year
    ) {
        MonthlySummaryReportDTO report = reportService.getMonthlySummary(year);
        return ResponseEntity.ok(report);
    }
}
```

---

## Repository Queries

### Custom Repository Methods

```java
public interface BusinessPaymentRepository extends JpaRepository<BusinessPayment, Long> {
    
    /**
     * Find all payments within date range (for revenue report).
     */
    List<BusinessPayment> findByPaidAtBetween(Instant from, Instant to);
    
    /**
     * Sum payments for invoice (for outstanding calculation).
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM BusinessPayment p WHERE p.invoiceId = ?1")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);
    
    /**
     * Find all payments for invoice (for outstanding detail).
     */
    List<BusinessPayment> findByInvoiceId(Long invoiceId);
}

public interface BusinessInvoiceRepository extends JpaRepository<BusinessInvoice, Long> {
    
    /**
     * Find invoices by status (for outstanding report).
     */
    List<BusinessInvoice> findByStatus(InvoiceStatus status);
    
    /**
     * Find invoices created in date range (for monthly summary).
     */
    List<BusinessInvoice> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
```

---

## Performance Considerations

### Optimization Strategies

| Strategy | Purpose | Implementation |
|----------|---------|-----------------|
| **Indexed Columns** | Fast queries | `paidAt` indexed on business_payments, `createdAt` on business_invoices |
| **Aggregation Queries** | Reduce data transfer | Use `@Query` with SUM, COUNT instead of fetching all rows |
| **Pagination (Future)** | Handle large datasets | Add pagination to monthly reports |
| **Caching (Future)** | Reduce repeated queries | Cache reports with 1-hour TTL |
| **Materialized Views (Future)** | Pre-computed metrics | PostgreSQL materialized views for complex aggregations |

### Query Optimization

**Current Queries (N+1 Risk):**
```java
// ❌ N+1: Fetches invoice, then customer, then for each invoice
List<BusinessInvoice> invoices = invoiceRepository.findAll();
for (BusinessInvoice invoice : invoices) {
    Customer customer = invoice.getCustomer(); // Lazy load
}
```

**Optimized (Eager Load):**
```java
// ✅ Single query with join
@Query("""
    SELECT i FROM BusinessInvoice i
    LEFT JOIN FETCH i.customer
    WHERE i.createdAtBetween(:start, :end)
""")
List<BusinessInvoice> findWithCustomer(LocalDateTime start, LocalDateTime end);
```

---

## Use Cases & Workflows

### 1. Monthly Financial Reporting

```
Executive Flow:
├─ 1st of month: Run monthly summary report for previous month
├─ 2. Review revenue by payment method (card vs UPI vs bank transfers)
├─ 3. Check collection rate (% of invoiced amount collected)
├─ 4. Compare vs previous months for trend analysis
└─ 5. Export to Excel for board presentation
```

### 2. Collection Management

```
Finance Team Flow:
├─ 1. Check outstanding report
├─ 2. Sort overdue invoices by days overdue
├─ 3. Prioritize collection calls (oldest first)
├─ 4. Send payment reminders to customers with 5+ days overdue
└─ 5. Track payment status changes
```

### 3. Tax Compliance (India)

```
Accounting Flow:
├─ 1. Run revenue report for fiscal quarter (Apr-Jun, Jul-Sep, etc.)
├─ 2. Verify GST tax amounts on invoices
├─ 3. Reconcile payments with bank deposits
├─ 4. Prepare GSTR-1 (outward supply) report
└─ 5. File quarterly GST return
```

### 4. Cash Flow Forecasting

```
CFO Flow:
├─ 1. Run outstanding report
├─ 2. Identify customer payment patterns (always late, always early)
├─ 3. Estimate cash inflow based on due dates
├─ 4. Plan working capital needs
└─ 5. Update cash flow forecast
```

---

## Metrics & KPIs

### Key Performance Indicators Tracked

| KPI | Formula | Target | Notes |
|-----|---------|--------|-------|
| **Collection Rate** | (Amount Collected / Amount Invoiced) × 100 | ≥ 95% | Monthly target |
| **Days Sales Outstanding (DSO)** | (Outstanding Balance / Daily Revenue) | ≤ 45 days | Lower is better |
| **Average Invoice Value** | Total Revenue / Invoice Count | — | Trend analysis |
| **Overdue Ratio** | Overdue Balance / Total Outstanding | ≤ 10% | Target < 10% |
| **Payment Method Mix** | Revenue by method | — | Track customer preferences |

---

## Data Privacy & Compliance

### Customer Data Handling

✅ **Included in Reports:**
- Customer name (needed for AR management)
- Invoice totals (financial reporting)
- Payment amounts and methods

❌ **NOT Included:**
- Customer email/phone (privacy)
- Customer address (privacy)
- Customer GSTIN (protected tax info)

### Audit Trail

- All reports generated are logged
- User, timestamp, report type, parameters logged
- Future: Non-repudiation with digital signatures

---

## Future Enhancements

🔜 **Custom Reports** — User-defined report builder  
🔜 **Export Formats** — PDF, Excel, CSV downloads  
🔜 **Report Scheduling** — Auto-generated monthly reports  
🔜 **Dashboards** — Real-time visualization (Charts.js, D3.js)  
🔜 **Forecasting** — Predictive revenue models (ML)  
🔜 **Variance Analysis** — Actual vs budget comparison  
🔜 **Drill-Down Analytics** — Click to details from summary  
🔜 **Multi-Tenant Aggregation** — Admin cross-tenant reports  

---

## Related Documentation

- [Payment Recording - Payments](./payments.md)
- [Invoicing Module - Business Invoices](./invoicing.md)
- [Customer Management - Customers](./customers.md)
- [Product Catalog - Products](./products.md)
