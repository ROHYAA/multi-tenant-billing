package com.mtbs.business.invoice.service;

import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.business.invoice.repository.BillItemRepository;
import com.mtbs.business.invoice.repository.BillRepository;
import com.mtbs.business.invoice.template.BillRenderOptions;
import com.mtbs.business.invoice.template.BillTemplateRenderer;
import com.mtbs.business.invoice.template.BillTemplateRendererRegistry;
import com.mtbs.business.invoice.template.CopyType;
import com.mtbs.business.payment.entity.Payment;
import com.mtbs.business.payment.repository.PaymentRepository;
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
 * the shop has configured, and delegates the actual drawing to it. See
 * com.mtbs.business.invoice.template.
 *
 * The renderer lookup key combines the shop's chosen style
 * (BillTemplate.code, e.g. "CASH_MEMO_V1") with its chosen paper size
 * (ShopSettings.paperSize, e.g. "A4") — "CASH_MEMO_V1:A4". This keeps
 * bill_templates as a style catalog (extensible to a genuine second style
 * later, each with its own A4/thermal renderers) while letting paperSize
 * alone drive today's A4-vs-thermal choice within the one style that exists.
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
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public byte[] generatePdf(Long invoiceId, BillRenderOptions options) {
        // Wrapped end-to-end: a raw JDBC/Hibernate failure here (e.g. this
        // tenant's schema missing a column a recent migration was supposed
        // to add) would otherwise fall through uncaught to the generic 500
        // handler, which deliberately hides the real exception from the
        // client — making this endpoint impossible to diagnose from the
        // browser/API response alone. Surfacing the real exception type and
        // message here (still a 400, not a stack trace) is what actually
        // lets a failure like that be identified without server-log access.
        try {
            Bill invoice = invoiceRepository.findById(invoiceId)
                    .orElseThrow(() -> ResourceException.notFound("Bill", invoiceId));

            List<BillItem> items = itemRepository.findAllByInvoiceId(invoiceId);
            Customer customer = customerService.getEntityById(invoice.getCustomerId());
            ShopSettings settings = shopSettingsService.getEntity();
            BillTemplate template = billTemplateService.getEntityById(settings.getBillTemplateId());

            String rendererKey = template.getCode() + ":" + settings.getPaperSize().name();
            BillTemplateRenderer renderer = rendererRegistry.get(rendererKey);

            // Payments are fetched here (not trusted from the caller-supplied options)
            // so every renderer can show which method(s) a bill was actually paid
            // with, without BillService/BillController needing to know about payments.
            List<Payment> payments = paymentRepository.findAllByInvoiceId(invoiceId);
            CopyType copyType = options != null ? options.copyType() : null;
            BillRenderOptions effectiveOptions = new BillRenderOptions(copyType, payments);

            log.info("Generating PDF for invoice={} using renderer={}", invoice.getInvoiceNumber(), rendererKey);

            byte[] pdf = renderer.render(invoice, items, customer, settings, effectiveOptions);
            log.info("PDF generated — invoiceNumber={}, bytes={}", invoice.getInvoiceNumber(), pdf.length);
            return pdf;
        } catch (ResourceException e) {
            throw e; // already a clean, intentional error (not found / bad input) — pass through as-is
        } catch (Exception e) {
            log.error("PDF generation failed unexpectedly — invoiceId={}", invoiceId, e);
            throw ResourceException.invalid(
                    "PDF generation failed: " + e.getClass().getSimpleName()
                            + (e.getMessage() != null ? " — " + e.getMessage() : ""));
        }
    }
}
