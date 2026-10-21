# PHASE 3 modules 4–9

**Date:** 2026-10-14  
**Scope:** Laravel `backend-laravel/` only. Java `src/` untouched. Angular unchanged.

## Modules

| # | Area | Routes |
|---|---|---|
| 4 | Customers | `/customers`, `/customers/walkin` |
| 5 | Products | `/products`, `/products/active`, deactivate/reactivate |
| 6–7 | Bills + payments | `/business-invoices`, `/business-payments` |
| 8 | Settings / print config / reports | `/shop-settings`, `/bill-templates`, `/reports/*` |
| 9 | Remainder | `/users`, `/roles`, `/permissions` |

## Billing math (matches Java)

- Line base = HALF_UP `(unitPrice × qty)` scale 2  
- Item tax = HALF_UP `lineBase × taxPct / 100` scale 2  
- Item total = line base + tax  
- Bill total = subtotal − discount + tax (reject if negative)  
- Invoice numbers from `number_series` (`INV-0001` when FY format is NONE)  
- CREDIT payments are **PENDING** and do not mark a bill PAID until `PATCH .../confirm`  
- Outstanding / revenue sums **CONFIRMED** payments only  
- Customer-level payment allocates FIFO oldest OPEN bill first  
- Writes run in DB transactions

## Deferred

- PDF download/preview: **HTTP 501** (`GEN_9001`) — iText/PDF still deferred  
- Attachments file storage (logo/signature URLs are null)  
- Outbox rows are stored; no mail worker yet

## Tests

Result: **23 passed** (123 assertions) including customer/product/bill/CREDIT-confirm and FIFO isolation. SQLite `:memory:`, not MySQL.
