package com.mtbs.business.invoice.template;

import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.settings.entity.ShopSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 80mm thermal receipt renderer. Registry key: "{billTemplate.code}:THERMAL_80MM".
 * Layout content lives in ThermalLayoutBuilder, shared with Thermal58Renderer —
 * this class only supplies the paper width.
 */
@Component
@RequiredArgsConstructor
public class Thermal80Renderer implements BillTemplateRenderer {

    private final ThermalLayoutBuilder layoutBuilder;

    @Override
    public String code() {
        return "CASH_MEMO_V1:THERMAL_80MM";
    }

    @Override
    public byte[] render(Bill invoice, List<BillItem> items, Customer customer, ShopSettings settings, BillRenderOptions options) {
        try {
            return layoutBuilder.build(80f, invoice, items, customer, settings, options);
        } catch (Exception e) {
            throw ResourceException.invalid("PDF generation failed: " + e.getMessage());
        }
    }
}
