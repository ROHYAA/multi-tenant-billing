package com.mtbs.business.invoice.template;

import com.mtbs.shared.exception.ResourceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class BillTemplateRendererRegistry {

    private final List<BillTemplateRenderer> renderers;
    private Map<String, BillTemplateRenderer> byCode;

    public BillTemplateRendererRegistry(List<BillTemplateRenderer> renderers) {
        this.renderers = renderers;
    }

    @PostConstruct
    void index() {
        byCode = renderers.stream()
                .collect(Collectors.toUnmodifiableMap(BillTemplateRenderer::code, r -> r));
        log.info("Registered {} bill template renderer(s): {}", byCode.size(), byCode.keySet());
    }

    public BillTemplateRenderer get(String code) {
        BillTemplateRenderer renderer = byCode.get(code);
        if (renderer == null) {
            throw ResourceException.notFound("BillTemplateRenderer", code);
        }
        return renderer;
    }
}
