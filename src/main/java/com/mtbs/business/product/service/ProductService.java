package com.mtbs.business.product.service;

import com.mtbs.business.product.dto.CreateProductRequest;
import com.mtbs.business.product.dto.ProductResponse;
import com.mtbs.business.product.dto.UpdateProductRequest;
import com.mtbs.business.product.entity.Product;
import com.mtbs.business.product.mapper.ProductMapper;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.business.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the tenant's product/service catalog.
 *
 * Key design decisions:
 *  - Products are DEACTIVATED, not hard-deleted — historical invoice line
 *    items reference product_id and must remain queryable.
 *  - Name uniqueness is enforced within tenant scope (schema isolation handles
 *    cross-tenant). This prevents duplicate entries in the catalog.
 *  - Price/tax changes on a product do NOT affect existing invoices —
 *    BusinessInvoiceItem snapshots values at creation time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        log.info("Creating product: name={}", request.getName());

        if (productRepository.existsByName(request.getName())) {
            throw ResourceException.alreadyExists("Product", request.getName());
        }

        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .taxPercentage(request.getTaxPercentage())
                .hsnSacCode(request.getHsnSacCode())
                .unit(request.getUnit())
                .isActive(true)
                .build();

        Product saved = productRepository.save(product);
        log.info("Product created — id={}, name={}", saved.getId(), saved.getName());
        return productMapper.toResponse(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ProductResponse getById(Long productId) {
        return productMapper.toResponse(findOrThrow(productId));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String search, Pageable pageable) {
        if (StringUtils.hasText(search)) {
            return productRepository.searchProducts(search.trim(), pageable)
                    .map(productMapper::toResponse);
        }
        return productRepository.findAllByOrderByIsActiveDescNameAsc(pageable)
                .map(productMapper::toResponse);
    }

    /**
     * Returns all active products for use in invoice line item selection.
     * Ordered alphabetically — not paginated (used in dropdowns).
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> listActiveForInvoice() {
        return productRepository.findAllByIsActiveTrueOrderByNameAsc()
                .stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public ProductResponse update(Long productId, UpdateProductRequest request) {
        Product product = findOrThrow(productId);

        // Name uniqueness — exclude self
        if (StringUtils.hasText(request.getName())
                && !request.getName().equalsIgnoreCase(product.getName())
                && productRepository.existsByNameAndIdNot(request.getName(), productId)) {
            throw ResourceException.alreadyExists("Product", request.getName());
        }

        if (StringUtils.hasText(request.getName()))       product.setName(request.getName());
        if (request.getDescription()  != null)            product.setDescription(request.getDescription());
        if (request.getPrice()        != null)            product.setPrice(request.getPrice());
        if (request.getTaxPercentage() != null)           product.setTaxPercentage(request.getTaxPercentage());
        if (request.getHsnSacCode()   != null)            product.setHsnSacCode(request.getHsnSacCode());
        if (request.getUnit()         != null)            product.setUnit(request.getUnit());

        Product saved = productRepository.save(product);
        log.info("Product updated — id={}", saved.getId());
        return productMapper.toResponse(saved);
    }

    // ── Deactivate (preferred over delete) ────────────────────────────────────

    /**
     * Deactivates a product — it can no longer be added to new invoices but
     * remains visible on historical invoice line items.
     * Use this instead of delete for discontinued products.
     */
    @Transactional
    public void deactivate(Long productId) {
        Product product = findOrThrow(productId);
        product.setIsActive(false);
        productRepository.save(product);
        log.info("Product deactivated — id={}", productId);
    }

    /**
     * Reactivates a previously deactivated product.
     */
    @Transactional
    public void reactivate(Long productId) {
        Product product = findOrThrow(productId);
        product.setIsActive(true);
        productRepository.save(product);
        log.info("Product reactivated — id={}", productId);
    }

    // ── Internal helper (used by BusinessInvoiceService) ─────────────────────

    /**
     * Returns the raw entity — used by BusinessInvoiceService to snapshot
     * price and tax at invoice line item creation time.
     */
    @Transactional(readOnly = true)
    public Product getEntityById(Long productId) {
        return findOrThrow(productId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Product", id));
    }

}