package com.nexzian.exception;

/**
 * Base for "too many requests" failures; mapped to HTTP 429 with a Retry-After header.
 *
 * Deliberately NOT an AuthenticationException: GlobalExceptionHandler maps every
 * AuthenticationException to a generic 401, which would hide the 429.
 */
public abstract class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    protected RateLimitExceededException(String messagePrefix, long retryAfterSeconds) {
        super(messagePrefix + " Please try again in " + waitText(retryAfterSeconds) + ".");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String waitText(long seconds) {
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = (seconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
