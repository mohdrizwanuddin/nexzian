package com.nexzian.util;

import java.sql.SQLException;

/**
 * Helpers for recognising WHY the database rejected a write.
 *
 * Application-level "does this already exist?" checks are only a courtesy: two concurrent
 * requests can both pass them. The database unique constraint is what actually guarantees
 * uniqueness, and the loser of such a race arrives here as a unique violation, which callers
 * translate into a normal "already taken" answer instead of an HTTP 500.
 */
public final class SqlErrors {

    /** SQLState 23505 = unique_violation (PostgreSQL, and also H2 used in tests). */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private SqlErrors() {
    }

    public static boolean isUniqueViolation(Throwable exception) {
        return UNIQUE_VIOLATION_SQL_STATE.equals(sqlState(exception));
    }

    /** Walks the cause chain and returns the first SQLState found, or null. */
    public static String sqlState(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof SQLException sqlException
                    && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
