package com.mtbs.business.invoice.template;

import com.mtbs.business.payment.entity.Payment;

import java.util.List;

/**
 * Per-request rendering options — as opposed to ShopSettings, which holds
 * standing shop-wide preferences. copyType is null when the caller doesn't
 * want a copy label printed (the common case for the OPEN download flow).
 *
 * payments is read-only render context (which methods this bill was actually
 * paid with, if any) — not a new business concept. It's populated by
 * BillPdfService right before calling the renderer, not by callers of
 * BillService.generatePdf(), which is why the single-arg constructor below
 * exists: it lets those call sites keep constructing a copyType-only options
 * value exactly as before.
 */
public record BillRenderOptions(CopyType copyType, List<Payment> payments) {

    public static final BillRenderOptions NONE = new BillRenderOptions(null, List.of());

    public BillRenderOptions(CopyType copyType) {
        this(copyType, List.of());
    }
}
