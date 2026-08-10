package com.mtbs.tenant.billtemplate.service;

import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.billtemplate.dto.BillTemplateResponse;
import com.mtbs.tenant.billtemplate.entity.BillTemplate;
import com.mtbs.tenant.billtemplate.repository.BillTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BillTemplateService {

    private final BillTemplateRepository billTemplateRepository;

    @Transactional(readOnly = true)
    public List<BillTemplateResponse> listActive() {
        return billTemplateRepository.findAllByIsActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    /** Used by BillPdfService to resolve a shop's configured template. */
    @Transactional(readOnly = true)
    public BillTemplate getEntityById(Long id) {
        return billTemplateRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("BillTemplate", id));
    }

    private BillTemplateResponse toResponse(BillTemplate template) {
        return BillTemplateResponse.builder()
                .id(template.getId())
                .code(template.getCode())
                .name(template.getName())
                .description(template.getDescription())
                .build();
    }
}
