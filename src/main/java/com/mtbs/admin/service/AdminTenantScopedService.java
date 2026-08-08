package com.mtbs.admin.service;

import com.mtbs.auth.dto.user.UserResponse;
import com.mtbs.auth.entity.User;
import com.mtbs.auth.mapper.UserMapper;
import com.mtbs.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated service bean to execute user queries inside a specific tenant schema
 * on behalf of a System Admin.
 * Called only AFTER the caller establishes TenantContext.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTenantScopedService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public Page<UserResponse> getUsersInTenant(Pageable pageable) {
        log.info("Fetching all users in current schema context (Admin Triggered)");
        return userRepository.findAll(pageable).map(userMapper::toResponseWithRole);
    }
}
