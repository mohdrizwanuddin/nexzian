package com.nexzian.exception;

/**
 * Thrown when a client tries to modify (edit, publish, add/remove/feature images of) a property
 * that is archived.
 *
 * Archived properties are hidden and frozen: allowing edits would let an archived listing be
 * changed (or quietly published) without the owner seeing it in any active view. The owner must
 * restore it first. Mapped to HTTP 409 by GlobalExceptionHandler, because the property exists
 * and belongs to the caller - the request conflicts with its current state - which is different
 * from a 404 (not found / not yours).
 */
public class PropertyArchivedException extends RuntimeException {

    public PropertyArchivedException() {
        super("This property is archived. Restore it before making changes.");
    }
}
