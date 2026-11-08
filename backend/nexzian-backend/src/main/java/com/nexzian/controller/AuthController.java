
package com.nexzian.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import com.nexzian.dto.LoginRequest;
import com.nexzian.dto.LoginResponse;
import com.nexzian.service.AuthService;
import com.nexzian.service.AuthSession;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    @Value("${app.auth.cookie.secure:false}")
    private boolean secureCookie;

    @Value("${app.jwt.expiration-ms}")
    private long tokenExpirationMs;

    @Value("${app.auth.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // User login
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        AuthSession session = authService.login(request);

        return ResponseEntity.ok()
                .header("Set-Cookie", accessCookie(session.response().getToken(), tokenExpirationMs).toString())
                .header("Set-Cookie", refreshCookie(session.refreshToken(), refreshTokenExpirationMs).toString())
                .body(session.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(
            @CookieValue(name = "nexzian_refresh", required = false) String refreshToken) {

        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        AuthSession session = authService.refresh(refreshToken);
        return ResponseEntity.noContent()
                .header("Set-Cookie", accessCookie(session.response().getToken(), tokenExpirationMs).toString())
                .header("Set-Cookie", refreshCookie(session.refreshToken(), refreshTokenExpirationMs).toString())
                .build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "nexzian_refresh", required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) authService.revoke(refreshToken);
        return ResponseEntity.noContent()
                .header("Set-Cookie", accessCookie("", 0).toString())
                .header("Set-Cookie", refreshCookie("", 0).toString())
                .build();
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken csrfToken) {
        return csrfToken;
    }

    private ResponseCookie accessCookie(String token, long maxAgeMs) {
        return ResponseCookie.from("nexzian_access", token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/v1")
                .maxAge(java.time.Duration.ofMillis(maxAgeMs))
                .build();
    }

    private ResponseCookie refreshCookie(String token, long maxAgeMs) {
        return ResponseCookie.from("nexzian_refresh", token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(java.time.Duration.ofMillis(maxAgeMs))
                .build();
    }
}
