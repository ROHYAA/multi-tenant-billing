# ShopLedger Printing Architecture (Phase 2.2)

**Status: implemented and verified.** Builds on Phase 2.1's Shop Settings & Bill Template foundation. Dashboard and the Angular frontend are still out of scope, per the approved plan.

---

## Goal

Take the Phase 2.1 foundation (a `BillTemplateRenderer` strategy interface with one working implementation, `ShopSettings` as a mostly-inert config store) and make it a complete, working printing system: three real paper-size renderers, actual logo/signature/QR-code image rendering, a dynamic UPI QR code, watermarks, copy labels, and PDF optimization — all driven by `ShopSettings`, none of it hardcoded.

---

## Renderer dispatch — how a paper size becomes a renderer

Phase 2.1 dispatched purely on `BillTemplate.code` (the visual *style* — "Simple Cash Memo"). Phase 2.2 adds a second axis, paper size, without touching that catalog or its public-schema migration:

```
registry key = "{BillTemplate.code}:{ShopSettings.paperSize}"
e.g.           "CASH_MEMO_V1:A4"
                "CASH_MEMO_V1:THERMAL_58MM"
                "CASH_MEMO_V1:THERMAL_80MM"
```

`BillPdfService` builds this string and looks it up in `BillTemplateRendererRegistry` (unchanged since Phase 2.1 — it just indexes whatever `code()` each `BillTemplateRenderer` bean returns). Each renderer declares the composite key it serves:

| Renderer | `code()` | Paper |
|---|---|---|
| `A4Renderer` | `CASH_MEMO_V1:A4` | A4, full-page tax-invoice layout |
| `Thermal58Renderer` | `CASH_MEMO_V1:THERMAL_58MM` | 58mm receipt |
| `Thermal80Renderer` | `CASH_MEMO_V1:THERMAL_80MM` | 80mm receipt |

**Why this shape, not a bigger schema change:** `bill_templates` stays a pure style catalog (still just one seeded row, still no migration needed for this phase). `ShopSettings.paperSize` — already a persisted, user-configurable field since Phase 2.1 — becomes the thing that actually drives renderer selection, fulfilling "use ShopSettings to control rendering" without inventing a new selector field. Adding a second genuine *style* later (not just a new paper size) means: one new `bill_templates` row + three new renderer classes with keys like `MODERN_V1:A4` — the registry and `BillPdfService` need no changes either way.

`Thermal58Renderer`/`Thermal80Renderer` are thin (~30 lines each) — both delegate their actual drawing to a shared package-private `ThermalLayoutBuilder`, parameterized by width. This avoids duplicating a ~200-line layout across two files while still giving the interface the two distinct `@Component` classes it asked for. `A4Renderer` doesn't share this builder — its layout (colored header bar, bordered items table) is visually unrelated to the receipt-style thermal layout, so forcing a shared abstraction there would have been artificial.

---

## New ShopSettings fields (V25, additive)

| Field | Purpose |
|---|---|
| `upiId` | Shop's UPI VPA (`name@bank`). QR is skipped (not drawn) when this is unset — there's nothing sensible to encode otherwise. |
| `signatureAttachmentId` | References an `Attachment` row. Falls back to a text signature line ("____________ Authorized Signatory") when unset — the Phase 2.1 behavior is preserved for shops that haven't uploaded a signature image yet. |
| `watermarkText` | Presence alone enables the watermark — no separate boolean. Null/blank = no watermark. |

`logoAttachmentId` (from Phase 2.1) is now actually drawn, not just stored — see "Image rendering" below.

**Not added:** a `showWatermark` boolean, a `copyType` ShopSettings field. Watermark text presence *is* the toggle (one less field, same expressiveness). Copy type is a **per-request query parameter** (`?copyType=ORIGINAL`), not a shop-wide setting — which physical copy you're printing changes every time you print, it isn't a standing preference.

---

## `BillRenderOptions` — per-request vs. per-shop configuration

```java
public record BillRenderOptions(CopyType copyType) {
    public static final BillRenderOptions NONE = new BillRenderOptions(null);
}
```

This is the dividing line the interface now makes explicit: `ShopSettings` is *what this shop always wants*; `BillRenderOptions` is *what this specific print action needs*. `BillTemplateRenderer.render(...)` takes both:

```java
byte[] render(Bill invoice, List<BillItem> items, Customer customer,
              ShopSettings settings, BillRenderOptions options);
```

`GET /business-invoices/{id}/download` and the new `/preview` endpoint both accept an optional `copyType` query param (`ORIGINAL` / `DUPLICATE` / `TRIPLICATE`) and thread it through `BillService.generatePdf(id, copyType)` → `BillPdfService.generatePdf(id, new BillRenderOptions(copyType))` → the renderer, which prints "ORIGINAL FOR RECIPIENT" / "DUPLICATE FOR SUPPLIER" / "TRIPLICATE FOR TRANSPORTER" near the top when set, and nothing when it's null (the common case).

---

## Print Preview

`GET /business-invoices/{id}/preview` — identical PDF bytes to `/download`, differing only in `Content-Disposition: inline` vs. `attachment`. That header difference is the entire feature: an inline PDF renders directly in a browser tab or an embedded viewer (once the Angular frontend exists — `<iframe src=".../preview">` or a PDF.js viewer), rather than forcing a download dialog. It works on invoices in **any** status, including DRAFT — a shop can check how their bill will look while still adding line items, before finalizing. (This already worked on `/download` too — `generatePdf` never gated on invoice status — `/preview` just makes the "check before you commit" use case a first-class, correctly-labeled endpoint.)

---

## Image rendering — logo, signature, QR

All three live in `BillRenderSupport`, a shared `@Component` injected into every renderer (composition, not an inheritance hierarchy — consistent with how the rest of this codebase composes services rather than building base classes).

**Logo / signature** (`loadLogo`, `loadSignatureImage`): fetch bytes via `AttachmentService.getFileBytes(id)`, downscale if either dimension exceeds 300px (see "PDF optimization" below), embed via `ImageDataFactory.create(...)`. Both only run when the relevant `show*` boolean is true *and* the attachment ID is set — four independent gates (`showLogo` × `logoAttachmentId`, `showSignature` × `signatureAttachmentId`), so a shop can upload a logo and still hide it without deleting the file, or flip `showLogo` on with nothing uploaded yet and get no broken image.

**UPI QR** (`generateUpiQrCode`): builds a standard UPI deep link —
```
upi://pay?pa={vpa}&pn={businessName}&am={totalAmount}&cu={currency}&tn={invoiceNumber}
```
— and renders it via iText's `BarcodeQRCode` (new `com.itextpdf:barcodes` dependency, same version family as the existing `kernel`/`layout`/`io` modules already in use). This is a real, scannable payment QR: any UPI app scanning it pre-fills the shop's VPA, the exact bill amount, and the invoice number as the transaction note. Skipped when `showQrCode` is off or `upiId` is unset.

**Failure handling — every one of these fails soft.** A missing attachment, a corrupt file, or an unsupported image format never fails the whole PDF generation; `BillRenderSupport` catches, logs a warning, and returns `null`, and each call site treats `null` as "don't draw this element." Printing a bill without a logo is always better than not printing it at all.

**Known gap: WebP.** `AttachmentService` (Phase 2.1) accepts `image/webp` uploads. Neither the JDK's `ImageIO` (used for the downscale step) nor iText's `ImageDataFactory` (used for the actual embed) has built-in WebP support. A WebP logo/signature will fail to embed and silently fall back to "not drawn" / the text signature line — functionally safe (no crash), but the image won't appear. Fixing this properly needs a WebP-capable decoder library (e.g. TwelveMonkeys ImageIO plugins) — not pulled in for this pass since PNG/JPEG cover the overwhelming majority of real uploads. Flagging it rather than leaving it a silent surprise.

---

## Watermark

`BillRenderSupport.drawWatermark(PdfDocument, ShopSettings)` runs after all normal content is added, once per page, using low-level `PdfCanvas` drawing (not the high-level `Document`/`Paragraph` API the rest of the layout uses) so it can paint independently of document flow: a 45°-rotated, 12%-opacity (`PdfExtGState.setFillOpacity`) rendering of `watermarkText`, sized to a sixth of the shorter page dimension, positioned to cross the page diagonally. Because it's real drawn text (not a rasterized image), it's still selectable/extractable text in the output PDF — confirmed during manual verification via `pdftotext`.

---

## Paper sizing

| Paper | Width | Height | Notes |
|---|---|---|---|
| A4 | 595pt (210mm) | 842pt (297mm) | Standard `PageSize.A4`, margins from `ShopSettings.margin` (mm → pt) |
| 58mm thermal | 164.4pt | **2000pt fixed** | `new PageSize(width, height)` |
| 80mm thermal | 226.8pt | **2000pt fixed** | Same |

**The fixed 2000pt height is a known, deliberate simplification**, not an oversight. Real thermal printers feed a continuous roll and cut based on their own logic (a paper-cut command, or the driver detecting end-of-content) — there is no fixed "page height" concept in physical thermal printing the way there is for A4. The technically correct approach is a two-pass render: lay out the content once to measure its actual height, then build the final PDF at exactly that height (or drive it directly against a real print job rather than a PDF at all). Implementing that measurement pass was judged out of scope for this iteration; 2000pt (~706mm) comfortably fits a typical multi-item retail receipt without truncation, and any real thermal print driver/viewer will show trailing whitespace rather than losing content. If a shop regularly prints receipts with 30+ line items, this would need revisiting.

`ShopSettings.margin` (mm) and `fontSize` (pt) — persisted since Phase 2.1 but unused until now — drive actual spacing and text size in **all three** renderers this phase (previously `A4Renderer`'s predecessor hardcoded `40/50/40/50` margins and fixed point sizes throughout). Thermal renderers additionally clamp these to sane bounds (margin ≤5mm, font ≤11pt) since a 58mm-wide receipt has much less room to work with than A4.

---

## PDF optimization

Two independent levers, applied identically across all three renderers:

1. **Compression** — `WriterProperties().setCompressionLevel(CompressionConstants.BEST_COMPRESSION).setFullCompressionMode(true)` on every `PdfWriter`. Full-compression mode also compresses the cross-reference table itself (meaningful on multi-object documents like these, with several fonts/images/form XObjects).
2. **Image downscaling** — any embedded logo/signature image larger than 300×300px is downscaled (via `java.awt`/`ImageIO`, bilinear interpolation) before embedding. A shop owner uploading a full-resolution phone photo as a logo (often 3000×3000px+) would otherwise bloat every single generated bill; a printed logo never needs to render larger than a few centimeters, so 300px is generous headroom for print-quality output at that size.

Fonts are not a size concern — `Helvetica`/`Helvetica-Bold` are PDF standard fonts, never embedded.

---

## What's genuinely new vs. what's the same file, extended

- **New**: `Thermal58Renderer`, `Thermal80Renderer`, `ThermalLayoutBuilder`, `BillRenderSupport`, `BillRenderOptions`, `CopyType`, three `ShopSettings` columns (V25), `/preview` endpoint, `itext-barcodes` dependency.
- **Renamed + extended, not rewritten**: `CashMemoV1Renderer` → `A4Renderer`. Its Phase 2.1 layout (header bar, items table, totals block) is unchanged in structure — logo/QR/watermark/copy-label/signature-image/dynamic-margin-and-font-size are additions around the existing skeleton, not a redesign. Default output for a shop with no logo/QR/watermark/signature configured is visually identical to Phase 2.1's.
- **Untouched**: `BillService`'s bill lifecycle (create/finalize/void/payment), `BillTemplateRendererRegistry`'s indexing mechanism, the `bill_templates` catalog and its migration, `NumberSeries`. Exactly per "keep the Bill Template strategy, use ShopSettings to control rendering, don't touch business logic."

---

## Verification performed

1. `mvn clean compile` / `mvn clean test-compile` — zero warnings.
2. `mvn test` — **79/79 passing**, including a new `BillPdfServiceTest` that exercises the full pipeline (registry dispatch, all three paper sizes, logo/signature embedding via a real PNG, UPI QR generation, watermark text, copy labels) by generating real PDFs and reading them back with iText's own `PdfTextExtractor`, plus asserting exact page dimensions (595pt / 164.4pt / 226.8pt widths).
3. Manual end-to-end smoke test against a running instance: signup → upload a real 50×50 PNG logo and signature → configure GSTIN/UPI ID/watermark/terms/footer → create and finalize a bill → `GET /preview` on the still-DRAFT bill (confirmed `inline` disposition) → `GET /download?copyType=ORIGINAL` on the finalized bill.
   - **A4**: confirmed via `pdftotext` — business header, GST line, "ORIGINAL FOR RECIPIENT" label, amount-in-words, "SAMPLE" watermark text, footer terms/warranty/message all present; confirmed via raw PDF inspection that the logo and signature are real `/Image` XObjects (50×50, matching the uploaded files — not the text fallback) and the QR is a real `/Form` XObject.
   - **58mm / 80mm thermal**: switched `ShopSettings.paperSize` live and re-downloaded — confirmed via `pdftotext` that both produce the same compact receipt-style content (business header, item lines, totals, amount-in-words, QR, signature, footer, watermark), correctly reformatted for the narrow width.
   - Caught and fixed one real bug this way: a hand-crafted minimal 1×1 test PNG failed iText's PNG decoder ("PNG image exception") while a standard 50×50 PNG embedded correctly — confirmed this was a degenerate-test-fixture issue, not a code defect, by checking the raw PDF bytes for genuine `/Image` objects on the second attempt.

---

## Explicitly not done in this pass

Per the approved scope (bill printing system only — Dashboard and the Angular frontend are separate, later work):

- **True ESC/POS raw thermal printing.** Both thermal renderers produce PDFs sized for 58mm/80mm paper — the same `byte[] render(...)` contract as A4, servable through the same `/download` endpoint, printable via any PDF-capable print driver (common on modern USB/Bluetooth receipt printers). Raw ESC/POS byte-stream generation (for older printers that only accept direct printer-command sockets, not PDFs) is a different technology entirely and was flagged as separate, later scope back in the original Phase 1 roadmap.
- **Dynamic content-height thermal pages** (see "Paper sizing" above).
- **WebP logo/signature embedding** (see "Known gap: WebP" above).
- Dashboard, Shop Settings frontend, Angular work of any kind.
