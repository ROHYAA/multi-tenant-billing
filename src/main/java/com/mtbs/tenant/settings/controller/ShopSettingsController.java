package com.mtbs.tenant.settings.controller;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.tenant.settings.dto.ShopSettingsResponse;
import com.mtbs.tenant.settings.dto.UpdateShopSettingsRequest;
import com.mtbs.tenant.settings.service.ShopSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/shop-settings")
@RequiredArgsConstructor
@Tag(name = "Shop Settings", description = "Business info, invoice numbering display, bill appearance, footer, and printer configuration")
@SecurityRequirement(name = "bearerAuth")
public class ShopSettingsController {

    private final ShopSettingsService shopSettingsService;

    @GetMapping
    @Operation(summary = "Get the current shop's settings")
    public ResponseEntity<ApiResponse<ShopSettingsResponse>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(shopSettingsService.getSettings(), "Shop settings fetched successfully"));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(
        summary = "Update the current shop's settings",
        description = "Partial update — send only the fields that changed. Requires TENANT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<ShopSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateShopSettingsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(shopSettingsService.updateSettings(request), "Shop settings updated successfully"));
    }
}
