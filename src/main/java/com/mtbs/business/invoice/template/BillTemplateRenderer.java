package com.mtbs.business.invoice.template;

import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.tenant.settings.entity.ShopSettings;

import java.util.List;

/**
 * One implementation per (template style, paper size) pair. Each
 * @Component implementation declares the registry key it renders;
 * BillTemplateRendererRegistry indexes them at startup. BillPdfService
 * builds the lookup key from bill_templates.code + ShopSettings.paperSize
 * (see BillPdfService for exactly how) — adding a new paper size for the
 * existing style, or a whole new style with its own paper-size variants,
 * means writing new classes + (for a new style) one catalog row, no
 * changes to BillService, BillController, or this interface.
 */
public interface BillTemplateRenderer {

    /** Registry key this renderer serves — see BillPdfService for how it's built. */
    String code();

    byte[] render(Bill invoice, List<BillItem> items, Customer customer, ShopSettings settings, BillRenderOptions options);
}
