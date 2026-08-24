package com.mtbs.business.customer.service;

import com.mtbs.business.customer.dto.CreateCustomerRequest;
import com.mtbs.business.customer.dto.CustomerResponse;
import com.mtbs.business.customer.dto.UpdateCustomerRequest;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.customer.mapper.CustomerMapper;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.business.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Manages tenant customers — the people/businesses the tenant sends invoices to.
 *
 * TenantContext is already set by JwtAuthenticationFilter for every
 * authenticated request. No manual context wiring needed here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        log.info("Creating customer: name={}, email={}", request.getName(), request.getEmail());

        // Email uniqueness within this tenant schema
        if (StringUtils.hasText(request.getEmail())
                && customerRepository.existsByEmail(request.getEmail())) {
            throw ResourceException.alreadyExists("Customer", request.getEmail());
        }

        Customer customer = Customer.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .address(request.getAddress())
                .gstin(request.getGstin())
                .build();

        Customer saved = customerRepository.save(customer);
        log.info("Customer created — id={}", saved.getId());
        return customerMapper.toResponse(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CustomerResponse getById(Long customerId) {
        return customerMapper.toResponse(findOrThrow(customerId));
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(String search, Pageable pageable) {
        if (StringUtils.hasText(search)) {
            return customerRepository.searchByKeyword(search.trim(), pageable)
                    .map(customerMapper::toResponse);
        }
        return customerRepository.findAll(pageable)
                .map(customerMapper::toResponse);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public CustomerResponse update(Long customerId, UpdateCustomerRequest request) {
        Customer customer = findOrThrow(customerId);

        // The system-seeded Walk-in Customer keeps its name permanently —
        // it's how the Billing screen finds and defaults to it.
        if (Boolean.TRUE.equals(customer.getIsWalkin())
                && StringUtils.hasText(request.getName())
                && !request.getName().equals(customer.getName())) {
            throw ResourceException.accessDenied("The Walk-in Customer cannot be renamed.");
        }

        // Email uniqueness — exclude self
        if (StringUtils.hasText(request.getEmail())
                && !request.getEmail().equalsIgnoreCase(customer.getEmail())
                && customerRepository.existsByEmailAndIdNot(request.getEmail(), customerId)) {
            throw ResourceException.alreadyExists("Customer", request.getEmail());
        }

        if (StringUtils.hasText(request.getName()))    customer.setName(request.getName());
        if (request.getEmail()   != null)              customer.setEmail(request.getEmail());
        if (request.getPhone()   != null)              customer.setPhone(request.getPhone());
        if (request.getAddress() != null)              customer.setAddress(request.getAddress());
        if (request.getGstin()   != null)              customer.setGstin(request.getGstin());

        Customer saved = customerRepository.save(customer);
        log.info("Customer updated — id={}", saved.getId());
        return customerMapper.toResponse(saved);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void delete(Long customerId) {
        Customer customer = findOrThrow(customerId);

        if (Boolean.TRUE.equals(customer.getIsWalkin())) {
            throw ResourceException.accessDenied("The Walk-in Customer cannot be deleted.");
        }

        // Block deletion if this customer has any non-void invoices
        if (customerRepository.hasActiveInvoices(customerId)) {
            throw ResourceException.invalid(
                "Cannot delete customer with open or paid invoices. Void all invoices first.");
        }

        // @SQLDelete handles the soft delete via UPDATE
        customerRepository.delete(customer);
        log.info("Customer soft-deleted — id={}", customerId);
    }

    // ── Internal helper (used by BillService) ─────────────────────

    /**
     * Fetches the customer entity directly — used by BillService
     * to validate the customer exists before creating an invoice.
     */
    @Transactional(readOnly = true)
    public Customer getEntityById(Long customerId) {
        return findOrThrow(customerId);
    }

    /**
     * Returns this shop's system-seeded Walk-in Customer (see V26 migration).
     * Every tenant schema has exactly one. Used by the Billing screen to
     * default-select a customer for cash/walk-in sales.
     */
    @Transactional(readOnly = true)
    public CustomerResponse getWalkInCustomer() {
        Customer customer = customerRepository.findByIsWalkinTrue()
                .orElseThrow(() -> ResourceException.notFound("Walk-in Customer", "default"));
        return customerMapper.toResponse(customer);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Customer findOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Customer", id));
    }

}