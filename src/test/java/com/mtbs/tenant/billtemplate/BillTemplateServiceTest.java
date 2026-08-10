package com.mtbs.tenant.billtemplate;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.billtemplate.dto.BillTemplateResponse;
import com.mtbs.tenant.billtemplate.entity.BillTemplate;
import com.mtbs.tenant.billtemplate.service.BillTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("BillTemplateService Integration Tests")
class BillTemplateServiceTest {

    @Autowired
    private BillTemplateService billTemplateService;

    @Test
    @DisplayName("listActive returns the seeded CASH_MEMO_V1 template")
    void listActive_returnsSeededTemplate() {
        List<BillTemplateResponse> templates = billTemplateService.listActive();

        assertFalse(templates.isEmpty());
        assertTrue(templates.stream().anyMatch(t -> "CASH_MEMO_V1".equals(t.getCode())));
    }

    @Test
    @DisplayName("getEntityById returns the matching template")
    void getEntityById_existingId_returnsTemplate() {
        Long id = billTemplateService.listActive().get(0).getId();

        BillTemplate template = billTemplateService.getEntityById(id);

        assertEquals(id, template.getId());
        assertNotNull(template.getCode());
    }

    @Test
    @DisplayName("getEntityById unknown id throws")
    void getEntityById_unknownId_throws() {
        assertThrows(ResourceException.class, () ->
            billTemplateService.getEntityById(999999L)
        );
    }
}
