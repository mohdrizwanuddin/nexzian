package com.nexzian.exception;

/** Too many agency registration attempts from one IP address. */
public class TooManyRegistrationAttemptsException extends RateLimitExceededException {

    public TooManyRegistrationAttemptsException(long retryAfterSeconds) {
        super("Too many registration attempts from this network.", retryAfterSeconds);
    }
}
