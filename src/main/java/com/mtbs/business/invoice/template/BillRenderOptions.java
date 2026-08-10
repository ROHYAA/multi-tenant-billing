package com.mtbs.business.invoice.template;

/**
 * Per-request rendering options — as opposed to ShopSettings, which holds
 * standing shop-wide preferences. copyType is null when the caller doesn't
 * want a copy label printed (the common case for the OPEN download flow).
 */
public record BillRenderOptions(CopyType copyType) {

    public static final BillRenderOptions NONE = new BillRenderOptions(null);
}
