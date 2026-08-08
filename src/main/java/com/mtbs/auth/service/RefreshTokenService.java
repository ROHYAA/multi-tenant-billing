package com.mtbs.auth.service;

import com.mtbs.auth.entity.RefreshToken;
import com.mtbs.auth.entity.User;
import com.mtbs.shared.exception.TokenException;
import com.mtbs.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private static final int REFRESH_TOKEN_VALIDITY_DAYS = 7;

    @Transactional
    public RefreshToken createRefreshToken(User user) {
        log.info("Creating new refresh token for user: {}", user.getEmail());
        revokeAllUserTokens(user);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setExpiryDate(Instant.now().plus(REFRESH_TOKEN_VALIDITY_DAYS, ChronoUnit.DAYS));
        refreshToken.setRevoked(false);

        return refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public RefreshToken validateRefreshToken(String token) {
        log.info("Validating refresh token...");
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(TokenException::invalid);

        if (refreshToken.getRevoked()) {
            throw TokenException.invalid();
        }

        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw TokenException.expired();
        }

        return refreshToken;
    }

    @Transactional
    public void revokeToken(String token) {
        log.info("Revoking refresh token...");
        refreshTokenRepository.findByToken(token).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    @Transactional
    public void revokeAllUserTokens(User user) {
        log.info("Revoking all tokens for user: {}", user.getEmail());
        refreshTokenRepository.deleteByUser(user);
    }
}
