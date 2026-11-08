package com.nexzian.exception;

/**
 * Thrown when valid credentials (or a valid refresh token) belong to an agency
 * that is not ACTIVE.
 *
 * Deliberately does NOT extend Spring Security's AuthenticationException:
 * GlobalExceptionHandler maps every AuthenticationException to a generic 401,
 * and this case must be reported as 403 with a clear message.
 */
public class TenantSuspendedException extends RuntimeException {

    public TenantSuspendedException(String message) {
        super(message);
    }
}
