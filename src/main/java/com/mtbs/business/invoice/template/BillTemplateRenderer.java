package com.mtbs.business.invoice.template;

import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.tenant.settings.entity.ShopSettings;

import java.util.List;

/**
 * One implementation per bill layout. Each @Component implementation
 * declares the bill_templates.code it renders; BillTemplateRendererRegistry
 * indexes them at startup. Adding Template 2/3 later means writing a new
 * class + one catalog row (V10 migration) — no changes to BillService,
 * BillController, or this interface.
 */
public interface BillTemplateRenderer {

    /** Must match a bill_templates.code value (see V10__create_bill_templates.sql). */
    String code();

    byte[] render(Bill invoice, List<BillItem> items, Customer customer, ShopSettings settings);
}
