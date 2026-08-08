package com.mtbs.auth.service;

import com.mtbs.auth.dto.user.*;
import com.mtbs.auth.entity.User;
import com.mtbs.auth.mapper.UserMapper;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.event.auth.AuthNotificationEvent;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.service.PlanLimitService;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.shared.util.SecurityUtils;
import com.mtbs.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Service for handling User operations.
 * TenantContext is automatically set by JwtAuthenticationFilter for all
 * endpoints mapped to this service.
 * Write operations are properly delegated to the transactional
 * UserScopedService bean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final UserScopedService userScopedService;
    private final PasswordEncoder passwordEncoder;
    private final TenantService tenantService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PlanLimitService planLimitService;
    private final UserMapper userMapper;
    private final PermissionCacheService permissionCacheService;

    public Page<UserResponse> getAllUsers(Pageable pageable) {
        log.info("Fetching all users in current schema context");
        return userRepository.findAllWithRole(pageable).map(userMapper::toResponseWithRole);
    }

    public UserResponse getUserById(Long userId) {
        log.info("Fetching user id: {}", userId);
        User user = userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));
        return userMapper.toResponseWithRole(user);
    }

    @CacheEvict(value = "dashboard", allEntries = true)
    public UserResponse createUser(CreateUserRequest request) {
        log.info("Delegating user creation: {}", request.getEmail());
        planLimitService.enforceUserLimit();
        User user = userScopedService.saveNewUser(request, passwordEncoder);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.CREATE)
                .entityType(AuditEntityType.USER)
                .entityId(user.getId())
                .entityName(user.getEmail())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(SecurityUtils.getCurrentTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("email", user.getEmail(), "role", user.getRole().getName()))
                .description("Created user: " + user.getEmail())
                .module("USER_MANAGEMENT")
                .build(), "User", user.getId());

        return userMapper.toResponseWithRole(user);
    }

    public UserResponse updateUser(Long userId, UpdateUserRequest request) {
        log.info("Delegating user update: {}", userId);
        User user = userScopedService.updateExistingUser(userId, request);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.UPDATE)
                .entityType(AuditEntityType.USER)
                .entityId(user.getId())
                .entityName(user.getEmail())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(SecurityUtils.getCurrentTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .description("Updated user: " + user.getEmail())
                .module("USER_MANAGEMENT")
                .build(), "User", userId);

        return userMapper.toResponseWithRole(user);
    }

    @CacheEvict(value = "dashboard", allEntries = true)
    public void deleteUser(Long userId) {
        log.info("Delegating user deletion: {}", userId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        userScopedService.softDeleteUser(userId, currentUserId);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.DELETE)
                .entityType(AuditEntityType.USER)
                .entityId(userId)
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(SecurityUtils.getCurrentTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .description("Deleted user with ID: " + userId)
                .module("USER_MANAGEMENT")
                .severity("WARN")
                .build(), "User", userId);
    }

    public UserResponse changeUserRole(Long userId, ChangeUserRoleRequest request) {
        log.info("Delegating role change for user: {}", userId);
        User user = userScopedService.changeRole(userId, request);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.UPDATE)
                .entityType(AuditEntityType.USER)
                .entityId(user.getId())
                .entityName(user.getEmail())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(SecurityUtils.getCurrentTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("role", user.getRole().getName()))
                .description("Changed user role to: " + user.getRole().getName())
                .module("USER_MANAGEMENT")
                .build(), "User", userId);

        permissionCacheService.evictUser(TenantContext.getSchemaName(), userId);

        return userMapper.toResponseWithRole(user);
    }

    public UserResponse changeUserStatus(Long userId, ChangeUserStatusRequest request) {
        log.info("Delegating status change for user: {}", userId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        User user = userScopedService.changeStatus(userId, request.getStatus(), currentUserId);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.USER)
                .entityId(user.getId())
                .entityName(user.getEmail())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(SecurityUtils.getCurrentTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("status", request.getStatus().name()))
                .description("Changed user status to: " + request.getStatus())
                .module("USER_MANAGEMENT")
                .build(), "User", userId);

        permissionCacheService.evictUser(TenantContext.getSchemaName(), userId);

        return userMapper.toResponseWithRole(user);
    }

    public UserResponse getMyProfile() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        log.info("Fetching own profile for user: {}", currentUserId);
        return getUserById(currentUserId);
    }

    public UserResponse updateMyProfile(UpdateOwnProfileRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        log.info("Delegating profile update for user: {}", currentUserId);
        User user = userScopedService.updateProfile(currentUserId, request, passwordEncoder);

        outboxEventPublisher.save(AuthNotificationEvent.builder()
                .eventType(NotificationEvent.PASSWORD_CHANGED)
                .recipientEmail(user.getEmail())
                .recipientName(user.getName())
                .tenantName(tenantService.fetchTenantName())
                .eventTime(Instant.now())
                .build(), "User", user.getId());

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PASSWORD_CHANGED)
                .entityType(AuditEntityType.USER)
                .entityId(user.getId())
                .entityName(user.getEmail())
                .whoUserId(user.getId())
                .whoUserEmail(user.getEmail())
                .whoUserName(user.getName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(SecurityUtils.getCurrentTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .description("User updated their profile")
                .module("USER_MANAGEMENT")
                .build(), "User", user.getId());

        return userMapper.toResponseWithRole(user);
    }
}
