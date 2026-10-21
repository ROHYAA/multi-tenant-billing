# Invoice PDF (Laravel)

Angular still calls `GET /api/v1/business-invoices/{id}/download` (print) and list reprint with `?copyType=DUPLICATE`. Preview uses the same bytes with `Content-Disposition: inline`.

Laravel now returns raw `application/pdf` (not the JSON envelope). Cross-tenant ids 404 via `BelongsToShop`.

Layout is a Helvetica cash-memo (shop header, TAX INVOICE, bill-to, lines, CGST/SGST half of stored tax, amount in words, UPI id as text, watermark text). It is **not** pixel-identical iText. Logos/QR images are omitted if files are missing (UPI id still prints). Marathi Mukta embedding is not included.

Paper: `A4` (595×842 pt), `THERMAL_58MM`, `THERMAL_80MM` from shop settings. No extra PHP extensions.

Tests: `BillPdfTest`.
