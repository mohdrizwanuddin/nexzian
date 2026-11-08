package com.nexzian.exception;

/** Too many failed login attempts for this email+IP, or from this IP. Says nothing about the email. */
public class TooManyLoginAttemptsException extends RateLimitExceededException {

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("Too many failed login attempts.", retryAfterSeconds);
    }
}
