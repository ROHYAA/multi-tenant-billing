package com.mtbs.auth.service;

import com.mtbs.auth.dto.user.ChangeUserRoleRequest;
import com.mtbs.auth.dto.user.CreateUserRequest;
import com.mtbs.auth.dto.user.UpdateOwnProfileRequest;
import com.mtbs.auth.dto.user.UpdateUserRequest;
import com.mtbs.shared.annotation.FeatureGate;
import com.mtbs.auth.entity.Role;
import com.mtbs.auth.entity.User;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.billing.UsageMetric;
import com.mtbs.shared.exception.AuthException;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.auth.repository.RoleRepository;
import com.mtbs.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Isolated tenant-scoped bean for User write operations.
 * Called only by UserService, AFTER the TenantContext is established.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserScopedService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Transactional
    @FeatureGate(metric = UsageMetric.ACTIVE_USERS)
    public User saveNewUser(CreateUserRequest request, PasswordEncoder encoder) {
        log.info("Saving new user: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw ResourceException.alreadyExists("User", request.getEmail());
        }

        Role role = roleRepository.findByName(request.getRoleName())
                .orElseThrow(() -> ResourceException.notFound("Role", request.getRoleName()));

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(encoder.encode(request.getPassword()));
        user.setRole(role);
        user.setStatus(Status.ACTIVE);

        User saved = userRepository.saveAndFlush(user);
        return userRepository.findByIdWithRole(saved.getId())
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(saved.getId())));
    }

    @Transactional
    public User updateExistingUser(Long userId, UpdateUserRequest request) {
        log.info("Updating existing user id: {}", userId);

        User user = userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));

        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw ResourceException.alreadyExists("User (email)", request.getEmail());
            }
            user.setEmail(request.getEmail());
        }

        userRepository.save(user);
        return userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));
    }

    @Transactional
    public User changeRole(Long userId, ChangeUserRoleRequest request) {
        log.info("Changing role for user id: {}", userId);

        User user = userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));

        Role role = roleRepository.findByName(request.getRoleName())
                .orElseThrow(() -> ResourceException.notFound("Role", request.getRoleName()));

        user.setRole(role);
        userRepository.save(user);
        return userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));
    }

    @Transactional
    public User changeStatus(Long userId, Status status, Long currentUserId) {
        log.info("Changing status for user id: {}", userId);

        if (userId.equals(currentUserId)) {
            throw ResourceException.accessDenied("Cannot change own status");
        }

        User user = userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));

        user.setStatus(status);
        userRepository.save(user);
        return userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));
    }

    @Transactional
    public User updateProfile(Long userId, UpdateOwnProfileRequest request, PasswordEncoder encoder) {
        log.info("User updating their own profile id: {}", userId);

        User user = userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));

        if (request.getName() != null) {
            user.setName(request.getName());
        }

        if (request.getNewPassword() != null && !request.getNewPassword().isBlank()) {
            if (request.getCurrentPassword() == null
                    || !encoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw AuthException.invalidCredentials();
            }
            user.setPassword(encoder.encode(request.getNewPassword()));
        }

        userRepository.save(user);
        return userRepository.findByIdWithRole(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));
    }

    @Transactional
    public void softDeleteUser(Long userId, Long currentUserId) {
        log.info("Soft deleting user id: {}", userId);

        if (userId.equals(currentUserId)) {
            throw ResourceException.accessDenied("Cannot delete yourself");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceException.notFound("User", String.valueOf(userId)));

        user.setDeleted(true);
        user.setDeletedAt(Instant.now());
        userRepository.save(user);
        // No return role not needed after delete
    }
}
