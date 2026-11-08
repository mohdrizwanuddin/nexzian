
package com.nexzian.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nexzian.dto.LoginRequest;
import com.nexzian.dto.LoginResponse;
import com.nexzian.entity.User;
import com.nexzian.entity.RefreshToken;
import com.nexzian.repository.RefreshTokenRepository;
import com.nexzian.exception.TenantSuspendedException;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;
import com.nexzian.security.TenantAccessPolicy;

@Service
public class AuthService {

    private static final String AGENCY_SUSPENDED_MESSAGE =
            "Your agency account is suspended. Please contact Nexzian support.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantAccessPolicy tenantAccessPolicy;

    @Value("${app.auth.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenRepository refreshTokenRepository,
            TenantAccessPolicy tenantAccessPolicy) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantAccessPolicy = tenantAccessPolicy;
    }

    @Transactional
    public AuthSession login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() ->
                        new BadCredentialsException("Invalid email or password"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BadCredentialsException(
                    "Your account is inactive. Please contact your administrator.");
        }

        if (!passwordEncoder.matches(
                request.getPassword(), user.getPassword())) {

            throw new BadCredentialsException(
                    "Invalid email or password");
        }

        // Checked only AFTER the password is verified, so the suspension message
        // is never shown to someone who does not know the correct password.
        if (!tenantAccessPolicy.isAllowed(user)) {
            throw new TenantSuspendedException(AGENCY_SUSPENDED_MESSAGE);
        }

        return createSession(user);
    }

    @Transactional
    public AuthSession refresh(String rawRefreshToken) {
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .filter(token -> token.getRevokedAt() == null)
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired refresh token"));

        User user = userRepository.findById(storedToken.getUserId())
                .filter(foundUser -> "ACTIVE".equals(foundUser.getStatus()))
                .orElseThrow(() -> new BadCredentialsException("User session is no longer active"));

        // Rejected BEFORE the token is revoked or rotated: a suspension does not
        // destroy the refresh token row, it simply makes it unusable while the
        // agency is not ACTIVE. After reactivation the same token works again.
        if (!tenantAccessPolicy.isAllowed(user)) {
            throw new TenantSuspendedException(AGENCY_SUSPENDED_MESSAGE);
        }

        storedToken.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(storedToken);
        return createSession(user);
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken)).ifPresent(token -> {
            token.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(token);
        });
    }

    private AuthSession createSession(User user) {
        String token = jwtService.generateToken(user);

        LoginResponse response = new LoginResponse();

        response.setToken(token);
        response.setUserId(user.getId());
        response.setTenantId(user.getTenantId());
        response.setFullName(user.getFullName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());

        return new AuthSession(response, createRefreshToken(user.getId()));
    }

    private String createRefreshToken(java.util.UUID userId) {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(userId);
        refreshToken.setTokenHash(hash(rawToken));
        refreshToken.setExpiresAt(LocalDateTime.now().plusNanos(refreshTokenExpirationMs * 1_000_000));
        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    private String hash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
