package com.mtbs.business.product.repository;

import com.mtbs.business.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** Name uniqueness check within tenant catalog. */
    boolean existsByName(String name);

    /** Name uniqueness excluding self (for update validation). */
    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * All active products — used when adding items to an invoice.
     * Deactivated products are excluded so they cannot be added to new invoices.
     */
    List<Product> findAllByIsActiveTrueOrderByNameAsc();

    /**
     * Paginated product listing with optional name search.
     * Returns active and inactive products — admin view shows the full catalog.
     *
     * IMPORTANT: Caller MUST pass a non-null search string. Never use this with null.
     * Using (:search IS NULL OR LOWER(...)) causes Hibernate to infer the parameter
     * type from the IS NULL check and bind it as bytea on PostgreSQL.
     * Handle the null/no-search case in the service layer (see ProductService.list()).
     */
    @Query("""
        SELECT p FROM Product p
        WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
        ORDER BY p.isActive DESC, p.name ASC
        """)
    Page<Product> searchProducts(@Param("search") String search, Pageable pageable);

    /**
     * Paginated list of all products — used when no search term is provided.
     * Separate method avoids any null parameter binding entirely.
     */
    Page<Product> findAllByOrderByIsActiveDescNameAsc(Pageable pageable);

    /**
     * Find by HSN/SAC code — used for GST compliance reporting.
     */
    List<Product> findAllByHsnSacCode(String hsnSacCode);
}