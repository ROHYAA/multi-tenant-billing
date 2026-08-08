package com.mtbs.auth.repository;

import com.mtbs.auth.entity.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {
    Optional<PlatformAdmin> findByEmail(String email);

    boolean existsByEmail(String email);
}
