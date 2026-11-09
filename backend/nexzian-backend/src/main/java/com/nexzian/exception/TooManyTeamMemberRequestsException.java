package com.nexzian.exception;

/** Too many team-member creation attempts from one agency; mapped to 429 with Retry-After. */
public class TooManyTeamMemberRequestsException extends RateLimitExceededException {

    public TooManyTeamMemberRequestsException(long retryAfterSeconds) {
        super("Too many attempts to add team members.", retryAfterSeconds);
    }
}
