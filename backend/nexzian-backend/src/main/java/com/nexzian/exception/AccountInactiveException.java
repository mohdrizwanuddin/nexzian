package com.nexzian.exception;

/**
 * Thrown when the correct password belongs to a deactivated user. Reported as 403.
 * Not an AuthenticationException, for the same reason as TenantSuspendedException.
 */
public class AccountInactiveException extends RuntimeException {

    public AccountInactiveException(String message) {
        super(message);
    }
}
