package com.mtbs.tenant.billtemplate.controller;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.tenant.billtemplate.dto.BillTemplateResponse;
import com.mtbs.tenant.billtemplate.service.BillTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/${api.version}/bill-templates")
@RequiredArgsConstructor
@Tag(name = "Bill Templates", description = "Read-only catalog of bill layouts a shop can select in Shop Settings")
@SecurityRequirement(name = "bearerAuth")
public class BillTemplateController {

    private final BillTemplateService billTemplateService;

    @GetMapping
    @Operation(summary = "List available bill templates")
    public ResponseEntity<ApiResponse<List<BillTemplateResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(billTemplateService.listActive(), "Bill templates fetched successfully"));
    }
}
