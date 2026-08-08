package com.mtbs.tenant.repository;

import com.mtbs.tenant.entity.Shop;
import com.mtbs.shared.enums.auth.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findBySchemaName(String schemaName);

    Optional<Shop> findByName(String name);

    boolean existsBySchemaName(String schemaName);

    boolean existsByName(String name);

    List<Shop> findAllByStatus(Status status);

    Page<Shop> findByStatus(Status status, Pageable pageable);

    boolean existsByOwnerEmail(String email);

    boolean existsBySlug(String slug);

    Optional<Shop> findBySlug(String slug);
}