package com.mtbs.auth.service;

import com.mtbs.admin.repository.AuditLogRepository;
import com.mtbs.auth.dto.auth.AuthResponse;
import com.mtbs.auth.dto.auth.TokenPair;
import com.mtbs.auth.entity.PlatformAdmin;
import com.mtbs.admin.entity.AuditLog;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.AuthException;
import com.mtbs.auth.repository.PlatformAdminRepository;
import com.mtbs.auth.security.JwtTokenProvider;
import com.mtbs.shared.util.CookieUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminAuthService {

    private final PlatformAdminRepository platformAdminRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    private final AuditLogRepository auditLogRepository;
    private final CookieUtils cookieUtils;

    public AuthResponse login(String email, String password) {
        log.info("SUPER_ADMIN login attempt for: {}", email);

        PlatformAdmin admin = platformAdminRepository.findByEmail(email)
                .orElseThrow(AuthException::invalidCredentials);

        if (!admin.isActive()) {
            throw AuthException.inactiveUser();
        }

        if (!passwordEncoder.matches(password, admin.getPassword())) {
            throw AuthException.invalidCredentials();
        }

        TokenPair tokens = generateAdminTokens(admin);

        auditLogRepository.save(AuditLog.builder()
                .whatAction(AuditAction.LOGIN)
                .whereEntityType(AuditEntityType.USER)
                .whereEntityId(admin.getId())
                .whereEntityName(admin.getEmail())
                .whoUserId(admin.getId())
                .whoUserEmail(admin.getEmail())
                .whoUserName(admin.getName())
                .whoRole("SUPER_ADMIN")
                .contextTenantName("PLATFORM")
                .description("Super admin logged in")
                .module("ADMIN_AUTH")
                .severity("INFO")
                .build());

        log.info("SUPER_ADMIN login successful for: {}", email);

        return AuthResponse.forSuperAdmin(
                jwtTokenProvider.getJwtExpiration() / 1000,
                Instant.now(),
                admin.getId(),
                admin.getEmail(),
                false
        );
    }

    public AuthResponse adminLogin(String email, String password, HttpServletResponse response) {
        log.info("SUPER_ADMIN login with cookie handling for: {}", email);

        PlatformAdmin admin = platformAdminRepository.findByEmail(email)
                .orElseThrow(AuthException::invalidCredentials);

        if (!admin.isActive()) {
            throw AuthException.inactiveUser();
        }

        if (!passwordEncoder.matches(password, admin.getPassword())) {
            throw AuthException.invalidCredentials();
        }

        TokenPair tokens = generateAdminTokens(admin);

        cookieUtils.addAdminAuthCookies(response, tokens.getAccessToken(), tokens.getRefreshToken());

        auditLogRepository.save(AuditLog.builder()
                .whatAction(AuditAction.LOGIN)
                .whereEntityType(AuditEntityType.USER)
                .whereEntityId(admin.getId())
                .whereEntityName(admin.getEmail())
                .whoUserId(admin.getId())
                .whoUserEmail(admin.getEmail())
                .whoUserName(admin.getName())
                .whoRole("SUPER_ADMIN")
                .contextTenantName("PLATFORM")
                .description("Super admin logged in with cookies")
                .module("ADMIN_AUTH")
                .severity("INFO")
                .build());

        log.info("SUPER_ADMIN login successful with cookie handling for: {}", email);

        return AuthResponse.forSuperAdmin(
                jwtTokenProvider.getJwtExpiration() / 1000,
                Instant.now(),
                admin.getId(),
                admin.getEmail(),
                false
        );
    }

    public TokenPair loginForTokens(String email, String password) {
        PlatformAdmin admin = platformAdminRepository.findByEmail(email)
                .orElseThrow(AuthException::invalidCredentials);
        if (!admin.isActive()) throw AuthException.inactiveUser();
        if (!passwordEncoder.matches(password, admin.getPassword())) throw AuthException.invalidCredentials();
        return generateAdminTokens(admin);
    }

    public AuthResponse refreshAdminToken(String rawRefreshToken) {
        log.info("SUPER_ADMIN refresh token attempt.");
        
        String redisKey = "super_admin_refresh:" + rawRefreshToken;
        String adminIdStr = stringRedisTemplate.opsForValue().get(redisKey);
        
        if (adminIdStr == null) {
            throw AuthException.invalidCredentials();
        }
        
        Long adminId = Long.parseLong(adminIdStr);
        PlatformAdmin admin = platformAdminRepository.findById(adminId)
                .orElseThrow(AuthException::invalidCredentials);
        
        stringRedisTemplate.delete(redisKey);
        TokenPair newTokens = generateAdminTokens(admin);
        
        auditLogRepository.save(AuditLog.builder()
                .whatAction(AuditAction.LOGIN)
                .whereEntityType(AuditEntityType.USER)
                .whereEntityId(admin.getId())
                .whereEntityName(admin.getEmail())
                .whoUserId(admin.getId())
                .whoUserEmail(admin.getEmail())
                .whoUserName(admin.getName())
                .whoRole("SUPER_ADMIN")
                .contextTenantName("PLATFORM")
                .description("Super admin token refreshed")
                .module("ADMIN_AUTH")
                .severity("INFO")
                .build());
        
        log.info("SUPER_ADMIN refresh successful for: {}", admin.getEmail());
        
        return AuthResponse.forSuperAdmin(
                jwtTokenProvider.getJwtExpiration() / 1000,
                Instant.now(),
                admin.getId(),
                admin.getEmail(),
                false
        );
    }

    public TokenPair refreshAdminTokenForTokens(String rawRefreshToken) {
        String redisKey = "super_admin_refresh:" + rawRefreshToken;
        String adminIdStr = stringRedisTemplate.opsForValue().get(redisKey);
        if (adminIdStr == null) throw AuthException.invalidCredentials();
        
        Long adminId = Long.parseLong(adminIdStr);
        PlatformAdmin admin = platformAdminRepository.findById(adminId)
                .orElseThrow(AuthException::invalidCredentials);
        
        stringRedisTemplate.delete(redisKey);
        return generateAdminTokens(admin);
    }

    public AuthResponse adminRefresh(HttpServletRequest httpRequest, HttpServletResponse response) {
        log.info("SUPER_ADMIN refresh with cookie handling");

        String cookieToken = cookieUtils.extractRefreshToken(httpRequest).orElse(null);
        if (!StringUtils.hasText(cookieToken)) {
            throw AuthException.invalidCredentials();
        }

        String redisKey = "super_admin_refresh:" + cookieToken;
        String adminIdStr = stringRedisTemplate.opsForValue().get(redisKey);

        if (adminIdStr == null) {
            throw AuthException.invalidCredentials();
        }

        Long adminId = Long.parseLong(adminIdStr);
        PlatformAdmin admin = platformAdminRepository.findById(adminId)
                .orElseThrow(AuthException::invalidCredentials);

        stringRedisTemplate.delete(redisKey);
        TokenPair newTokens = generateAdminTokens(admin);

        cookieUtils.addAdminAuthCookies(response, newTokens.getAccessToken(), newTokens.getRefreshToken());

        auditLogRepository.save(AuditLog.builder()
                .whatAction(AuditAction.LOGIN)
                .whereEntityType(AuditEntityType.USER)
                .whereEntityId(admin.getId())
                .whereEntityName(admin.getEmail())
                .whoUserId(admin.getId())
                .whoUserEmail(admin.getEmail())
                .whoUserName(admin.getName())
                .whoRole("SUPER_ADMIN")
                .contextTenantName("PLATFORM")
                .description("Super admin token refreshed with cookies")
                .module("ADMIN_AUTH")
                .severity("INFO")
                .build());

        log.info("SUPER_ADMIN refresh successful with cookie handling for: {}", admin.getEmail());

        return AuthResponse.forSuperAdmin(
                jwtTokenProvider.getJwtExpiration() / 1000,
                Instant.now(),
                admin.getId(),
                admin.getEmail(),
                false
        );
    }

    public void adminLogout(HttpServletResponse response) {
        log.info("SUPER_ADMIN logout with cookie clearing");
        cookieUtils.clearAdminAuthCookies(response);
    }

    private TokenPair generateAdminTokens(PlatformAdmin admin) {
        String accessToken = jwtTokenProvider.generateSuperAdminToken(admin);
        String refreshToken = java.util.UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(
                "super_admin_refresh:" + refreshToken,
                String.valueOf(admin.getId()),
                jwtTokenProvider.getRefreshExpiration(),
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
        return TokenPair.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
}
