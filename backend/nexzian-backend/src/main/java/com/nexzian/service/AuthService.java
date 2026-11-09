
package com.nexzian.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

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
import com.nexzian.exception.AccountInactiveException;
import com.nexzian.exception.TenantSuspendedException;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;
import com.nexzian.security.LoginAttemptLimiter;
import com.nexzian.security.TenantAccessPolicy;
import com.nexzian.util.EmailNormalizer;

@Service
public class AuthService {

    private static final String AGENCY_SUSPENDED_MESSAGE =
            "Your agency account is suspended. Please contact Nexzian support.";

    private static final String ACCOUNT_INACTIVE_MESSAGE =
            "Your account is inactive. Please contact your administrator.";

    /** BCrypt ignores everything after this many bytes of the UTF-8 encoded password. */
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    /**
     * Fixed BCrypt hash (cost 10, same as the application's encoder) of a random
     * throw-away value that belongs to no account. Checked when the email is unknown so
     * the same BCrypt work is done as for a real user. Its cost factor is verified by
     * LoginTimingProtectionTests so a future change of strength cannot silently skew timing.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$DRpIiYN2JAAIVdfzfJ833uCfVesDfbAZb5fQNeVUtag3DDMHK0S3W";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantAccessPolicy tenantAccessPolicy;
    private final LoginAttemptLimiter loginAttemptLimiter;

    @Value("${app.auth.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenRepository refreshTokenRepository,
            TenantAccessPolicy tenantAccessPolicy,
            LoginAttemptLimiter loginAttemptLimiter) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantAccessPolicy = tenantAccessPolicy;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    /**
     * Order of checks (each step deliberately comes before the next):
     *  1. rate limit (reserves an attempt; 429 if locked - same answer whether or not the
     *     email exists);
     *  2. verify the password, running a real BCrypt comparison even for an unknown email
     *     so response time does not reveal which emails exist;
     *  3. only with a CORRECT password: inactive user (403), suspended agency (403).
     * Wrong password and unknown email both give the same generic 401 and both count as
     * failed attempts. A correct password is never a failed attempt, even if the account
     * is then refused as inactive or suspended.
     */
    @Transactional
    public AuthSession login(LoginRequest request, String clientIp) {

        // One canonical form for BOTH the lockout counter and the account lookup, so "OWNER@X.com"
        // and "owner@x.com" are the same account AND share one failed-attempt counter. All stored
        // emails are canonical (entity setter + database CHECK), so an exact match on the
        // normalized value finds the account regardless of how the user typed it.
        String normalizedEmail = EmailNormalizer.normalize(request.getEmail());
        LoginAttemptLimiter.Attempt attempt =
                loginAttemptLimiter.beginAttempt(LoginAttemptLimiter.normalizeEmail(normalizedEmail), clientIp);

        Optional<User> found = userRepository.findByEmail(normalizedEmail);

        String hashToCheck = found.map(User::getPassword).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordMatches(request.getPassword(), hashToCheck);

        if (found.isEmpty() || !passwordMatches) {
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = found.get();

        // The credentials were right: this was not a failed guess.
        loginAttemptLimiter.credentialsVerified(attempt);

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AccountInactiveException(ACCOUNT_INACTIVE_MESSAGE);
        }

        if (!tenantAccessPolicy.isAllowed(user)) {
            throw new TenantSuspendedException(AGENCY_SUSPENDED_MESSAGE);
        }

        return createSession(user);
    }

    /**
     * BCrypt only looks at the first 72 bytes of a password. In this Spring Security
     * version encode() REJECTS a longer password, but matches() silently TRUNCATES the
     * candidate - so a stored 72-byte password would also "match" itself followed by any
     * extra characters. Because encode() refuses to store anything longer than 72 bytes, no
     * correct password can exceed that length: a longer candidate is refused outright (and
     * counted as a failed attempt by the caller) without ever reaching BCrypt.
     * This also happens identically for known and unknown emails, so it reveals nothing
     * about which accounts exist.
     */
    private boolean passwordMatches(String rawPassword, String hash) {
        if (rawPassword == null
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
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
