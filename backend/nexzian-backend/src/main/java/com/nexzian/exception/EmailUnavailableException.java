package com.nexzian.exception;

/**
 * An email address cannot be used for a new account because it is already registered.
 *
 * The message is a FIXED, generic string on purpose: the same answer is given whether the address
 * belongs to this agency, to another agency, or lost a concurrent race. A team-member form that
 * said "already exists in another agency" (or answered differently per case) would let an owner
 * probe which addresses have accounts anywhere on the platform. The address itself is never
 * included. Mapped to HTTP 409 by GlobalExceptionHandler.
 */
public class EmailUnavailableException extends RuntimeException {

    public static final String MESSAGE =
            "This email address cannot be used. Please enter a different email address.";

    public EmailUnavailableException() {
        super(MESSAGE);
    }
}
