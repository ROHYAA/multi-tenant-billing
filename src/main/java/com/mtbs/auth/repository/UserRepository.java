package com.mtbs.auth.repository;

import com.mtbs.auth.entity.User;
import com.mtbs.shared.enums.auth.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByStatus(Status status);

    long countByDeletedFalseAndStatus(Status status);

    java.util.List<User> findByRoleId(Long roleId);

    // for limiting
    @Query("SELECT COUNT(u) FROM User u")
    long countActiveUsers();

    @Query("SELECT u FROM User u JOIN FETCH u.role")
    Page<User> findAllWithRole(Pageable pageable);

    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.id = :id")
    Optional<User> findByIdWithRole(@Param("id") Long id);

    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.email = :email")
    Optional<User> findByEmailWithRole(@Param("email") String email);

}
