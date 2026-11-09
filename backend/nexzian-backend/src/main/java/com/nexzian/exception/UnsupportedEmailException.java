package com.nexzian.exception;

/**
 * An email address violates the supported-address policy (printable ASCII only, no whitespace or
 * control characters, at most 150 characters; see EmailNormalizer).
 *
 * Normally request validation rejects such input first (HTTP 400, field message). This exception
 * is the SAFETY NET for code that stores an email without going through request validation - an
 * internal service call, a script, a future endpoint - so the policy cannot be bypassed. Mapped to
 * HTTP 400 by GlobalExceptionHandler. The message is a fixed policy sentence and never contains
 * the address.
 */
public class UnsupportedEmailException extends IllegalArgumentException {

    public UnsupportedEmailException(String message) {
        super(message);
    }
}
