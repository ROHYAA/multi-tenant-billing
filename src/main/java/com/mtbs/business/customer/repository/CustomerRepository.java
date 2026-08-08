package com.mtbs.business.customer.repository;

import com.mtbs.business.customer.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByRazorpayCustomerId(String razorpayCustomerId);

    /**
     * Paginated search — called only when search term is non-blank.
     * CAST(:search AS string) is NOT used — the parameter is always a non-null
     * String here so Hibernate resolves the type correctly as text.
     *
     * Root cause of original bug: (:search IS NULL OR LOWER(c.name) LIKE ...)
     * causes Hibernate to infer the :search param type from the IS NULL check
     * rather than the LIKE operand, binding it as bytea on PostgreSQL.
     * Fix: never pass null into a JPQL query that uses the param in LOWER/LIKE.
     * The null case is handled in CustomerService.list() before calling here.
     */
    @Query("""
        SELECT c FROM Customer c
        WHERE LOWER(c.name)  LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%'))
        ORDER BY c.createdAt DESC
        """)
    Page<Customer> searchByKeyword(@Param("search") String search, Pageable pageable);

    /**
     * Paginated list of all customers — called when no search term is provided.
     * Separate method avoids any null parameter binding entirely.
     */
    Page<Customer> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Check whether this customer has any non-void invoices.
     * Blocks deletion of customers with open or paid invoices.
     */
    @Query("""
        SELECT COUNT(i) > 0
        FROM BusinessInvoice i
        WHERE i.customerId = :customerId
          AND i.status <> com.mtbs.shared.enums.billing.InvoiceStatus.VOID
        """)
    boolean hasActiveInvoices(@Param("customerId") Long customerId);
}