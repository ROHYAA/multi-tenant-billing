package com.mtbs.business.customer.controller;

import com.mtbs.business.customer.dto.CreateCustomerRequest;
import com.mtbs.business.customer.dto.CustomerResponse;
import com.mtbs.business.customer.dto.UpdateCustomerRequest;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.business.customer.service.CustomerService;
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

@RestController
@RequestMapping("/api/${api.version}/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Manage tenant customers — the people and businesses you bill")
@SecurityRequirement(name = "bearerAuth")
public class CustomerController {

    private final CustomerService customerService;

    // ── POST /api/customers ───────────────────────────────────────────────────

    @PostMapping
    @TrackUsage(metric = UsageMetric.API_CALLS)
    @PreAuthorize("hasAuthority('PERMISSION_CUSTOMER_MANAGE')")
    @Operation(
        summary = "Create a customer",
        description = "Creates a new customer and syncs them to Razorpay (best-effort). " +
                      "Email must be unique within the tenant. GSTIN is optional and validated " +
                      "as a 15-character alphanumeric string when provided. " +
                      "Requires CUSTOMER_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<CustomerResponse>> create(
            @Valid @RequestBody CreateCustomerRequest request) {

        CustomerResponse response = customerService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Customer created successfully"));
    }

    // ── GET /api/customers ────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_CUSTOMER_MANAGE')")
    @Operation(
        summary = "List customers",
        description = "Returns a paginated list of all customers in this tenant. " +
                      "Optional 'search' param filters by name or email (case-insensitive). " +
                      "Ordered by creation date descending. " +
                      "Requires CUSTOMER_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PageResponse<CustomerResponse>>> list(
            @Parameter(description = "Optional search term — matches name or email")
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<CustomerResponse> response = customerService.list(search, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "Customers fetched successfully"));
    }

    // ── GET /api/customers/{id} ───────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_CUSTOMER_MANAGE')")
    @Operation(
        summary = "Get customer by ID",
        description = "Returns a single customer record. " +
                      "Returns 404 if the customer does not exist or has been deleted. " +
                      "Requires CUSTOMER_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<CustomerResponse>> getById(@PathVariable Long id) {
        CustomerResponse response = customerService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Customer fetched successfully"));
    }

    // ── PUT /api/customers/{id} ───────────────────────────────────────────────

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_CUSTOMER_MANAGE')")
    @Operation(
        summary = "Update a customer",
        description = "Updates customer fields. All fields are optional — send only what changed. " +
                      "Email uniqueness is enforced (excluding the customer being updated). " +
                      "Requires CUSTOMER_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<CustomerResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {

        CustomerResponse response = customerService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Customer updated successfully"));
    }

    // ── DELETE /api/customers/{id} ────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_CUSTOMER_MANAGE')")
    @Operation(
        summary = "Delete a customer",
        description = "Soft-deletes a customer. " +
                      "Returns 400 if the customer has any open or paid invoices — " +
                      "void all invoices before deleting. " +
                      "Requires CUSTOMER_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        customerService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Customer deleted successfully"));
    }
}