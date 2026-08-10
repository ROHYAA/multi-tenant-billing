package com.mtbs.business.invoice.service;

import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.business.invoice.repository.BillItemRepository;
import com.mtbs.business.invoice.repository.BillRepository;
import com.mtbs.business.invoice.template.BillTemplateRenderer;
import com.mtbs.business.invoice.template.BillTemplateRendererRegistry;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.billtemplate.entity.BillTemplate;
import com.mtbs.tenant.billtemplate.service.BillTemplateService;
import com.mtbs.tenant.settings.entity.ShopSettings;
import com.mtbs.tenant.settings.service.ShopSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestration only — fetches the data, resolves which BillTemplateRenderer
 * the shop has configured (ShopSettings.billTemplateId -> BillTemplate.code),
 * and delegates the actual drawing to it. See com.mtbs.business.invoice.template.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillPdfService {

    private final BillRepository invoiceRepository;
    private final BillItemRepository itemRepository;
    private final CustomerService customerService;
    private final ShopSettingsService shopSettingsService;
    private final BillTemplateService billTemplateService;
    private final BillTemplateRendererRegistry rendererRegistry;

    @Transactional(readOnly = true)
    public byte[] generatePdf(Long invoiceId) {
        Bill invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("Bill", invoiceId));

        List<BillItem> items = itemRepository.findAllByInvoiceId(invoiceId);
        Customer customer = customerService.getEntityById(invoice.getCustomerId());
        ShopSettings settings = shopSettingsService.getEntity();
        BillTemplate template = billTemplateService.getEntityById(settings.getBillTemplateId());
        BillTemplateRenderer renderer = rendererRegistry.get(template.getCode());

        log.info("Generating PDF for invoice={} using template={}", invoice.getInvoiceNumber(), template.getCode());

        byte[] pdf = renderer.render(invoice, items, customer, settings);
        log.info("PDF generated — invoiceNumber={}, bytes={}", invoice.getInvoiceNumber(), pdf.length);
        return pdf;
    }
}
