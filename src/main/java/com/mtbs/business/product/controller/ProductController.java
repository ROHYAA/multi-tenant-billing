package com.mtbs.business.product.controller;

import com.mtbs.business.product.dto.CreateProductRequest;
import com.mtbs.business.product.dto.ProductResponse;
import com.mtbs.business.product.dto.UpdateProductRequest;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.business.product.service.ProductService;
import com.mtbs.shared.dto.common.PageResponse;
import com.mtbs.shared.annotation.TrackUsage;
import com.mtbs.shared.enums.billing.UsageMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/${api.version}/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Manage tenant product and service catalog")
@SecurityRequirement(name = "bearerAuth")
public class ProductController {

    private final ProductService productService;

    // ── POST /api/products ────────────────────────────────────────────────────

    @PostMapping
    @TrackUsage(metric = UsageMetric.API_CALLS)
    @PreAuthorize("hasAuthority('PERMISSION_PRODUCT_MANAGE')")
    @Operation(
        summary = "Create a product",
        description = "Adds a new product or service to the catalog. " +
                      "Product name must be unique within the tenant. " +
                      "taxPercentage defaults to 0 (tax-exempt) if not provided. " +
                      "Requires PRODUCT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<ProductResponse>> create(
            @Valid @RequestBody CreateProductRequest request) {

        ProductResponse response = productService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Product created successfully"));
    }

    // ── GET /api/products ─────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_PRODUCT_MANAGE')")
    @Operation(
        summary = "List products",
        description = "Returns a paginated list of all products (active and inactive). " +
                      "Optional 'search' param filters by name (case-insensitive). " +
                      "Ordered by active status (active first), then name alphabetically. " +
                      "Requires PRODUCT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> list(
            @Parameter(description = "Optional search term — matches product name")
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<ProductResponse> response = productService.list(search, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "Products fetched successfully"));
    }

    // ── GET /api/products/active ──────────────────────────────────────────────

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
        summary = "List active products for invoice",
        description = "Returns all active products ordered alphabetically. " +
                      "Used to populate the product dropdown when creating invoice line items. " +
                      "Not paginated — returns all active products in a single response. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<List<ProductResponse>>> listActiveForInvoice() {
        List<ProductResponse> response = productService.listActiveForInvoice();
        return ResponseEntity.ok(ApiResponse.success(response, "Active products fetched successfully"));
    }

    // ── GET /api/products/{id} ────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_PRODUCT_MANAGE')")
    @Operation(
        summary = "Get product by ID",
        description = "Returns a single product record including deactivated products. " +
                      "Returns 404 if the product does not exist or has been hard-deleted. " +
                      "Requires PRODUCT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<ProductResponse>> getById(@PathVariable Long id) {
        ProductResponse response = productService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Product fetched successfully"));
    }

    // ── PUT /api/products/{id} ────────────────────────────────────────────────

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_PRODUCT_MANAGE')")
    @Operation(
        summary = "Update a product",
        description = "Updates product fields. All fields are optional — send only what changed. " +
                      "Name uniqueness is enforced (excluding the product being updated). " +
                      "Price/tax changes do NOT affect existing invoice line items — " +
                      "values are snapshotted at invoice creation time. " +
                      "Requires PRODUCT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<ProductResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request) {

        ProductResponse response = productService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Product updated successfully"));
    }

    // ── DELETE /api/products/{id} ─────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_PRODUCT_MANAGE')")
    @Operation(
        summary = "Deactivate a product",
        description = "Deactivates a product — it can no longer be added to new invoices. " +
                      "Historical invoice line items referencing this product are unaffected. " +
                      "Prefer deactivation over deletion for discontinued products. " +
                      "Requires PRODUCT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        productService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Product deactivated successfully"));
    }

    // ── POST /api/products/{id}/reactivate ────────────────────────────────────

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('PERMISSION_PRODUCT_MANAGE')")
    @Operation(
        summary = "Reactivate a product",
        description = "Re-enables a previously deactivated product so it can be added " +
                      "to new invoices again. " +
                      "Requires PRODUCT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable Long id) {
        productService.reactivate(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Product reactivated successfully"));
    }
}