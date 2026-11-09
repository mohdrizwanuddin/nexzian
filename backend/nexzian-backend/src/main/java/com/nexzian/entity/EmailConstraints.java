package com.nexzian.entity;

/**
 * The database CHECK expressions for the email columns of {@link User} and {@link Tenant}, defined
 * ONCE so both entities, the tests and the migration script stay in step.
 *
 * <h3>The rules</h3>
 * <ul>
 *   <li><b>canonical</b> - {@code email = lower(trim(email))}: stored emails are trimmed and
 *       lowercase. Identical in every database.</li>
 *   <li><b>supported (ASCII)</b> - only printable ASCII 0x21-0x7E: no whitespace, no control
 *       characters (including DEL 0x7F), nothing non-ASCII.</li>
 * </ul>
 *
 * <h3>Why the ASCII rule has two spellings (portability)</h3>
 * No short SQL expression works on both PostgreSQL and H2 (the test database): PostgreSQL's regex
 * operator is {@code ~}, H2 uses REGEXP_LIKE. So there are two equivalent spellings:
 * <ul>
 *   <li>{@link #ASCII_POSTGRESQL} - the exact production rule, a one-line regex. It is installed on
 *       <b>fresh</b> PostgreSQL schemas by Hibernate (through {@code @DialectOverride.Check} on the
 *       entities) and on <b>existing</b> PostgreSQL databases by
 *       docs/r4-email-integrity-migration.sql, so both end up with the identical constraint.</li>
 *   <li>{@link #ASCII_PORTABLE} - an equivalent built from functions that H2 and PostgreSQL both
 *       support: {@code octet_length = char_length} rejects any non-ASCII character (in a UTF-8
 *       database) and a {@code position(chr(n) in email) = 0} term rejects the space, every other
 *       control character and DEL. It is what H2 test schemas use, so tests exercise the same
 *       rule, and it is the fallback for any dialect without a specific override.
 *       (translate() is NOT used: H2 does not delete characters when the replacement is empty,
 *       PostgreSQL does.) chr(0) is omitted: PostgreSQL text cannot contain NUL.</li>
 * </ul>
 *
 * <h3>How the two spellings are kept honest</h3>
 * <ul>
 *   <li>GeneratedSchemaChecksTests generates the CREATE TABLE script Hibernate would emit for
 *       PostgreSQL and for H2 (as text, no database) and asserts which spelling each one gets.
 *       This matters: Hibernate silently ignores {@code @DialectOverride.Check} when an entity has
 *       more than one class-level {@code @Check}, so the entities declare exactly one.</li>
 *   <li>EmailConstraintConsistencyTests evaluates {@link #ASCII_PORTABLE} on H2, and applies the
 *       regex from {@link #ASCII_POSTGRESQL} in Java, for every character, and compares both with
 *       the application policy (EmailNormalizer.findProblem).</li>
 *   <li>MigrationScriptConsistencyTests checks the migration script installs the same expressions.</li>
 * </ul>
 * Not covered by any automated test: PostgreSQL actually executing these statements. Rehearse the
 * migration (and a fresh-schema start) on a real PostgreSQL instance.
 */

public final class EmailConstraints {

    private EmailConstraints() {
    }

    /** Stored emails are trimmed and lowercase. */
    public static final String CANONICAL = "email = lower(trim(email))";

    /** Printable ASCII 0x21-0x7E, one or more characters. PostgreSQL only. */
    public static final String ASCII_POSTGRESQL = "email ~ '^[!-~]+$'";

    /** The same rule from functions that H2 and PostgreSQL both support. See the class comment. */
    public static final String ASCII_PORTABLE =
            "octet_length(email) = char_length(email)"
            + " and position(chr(1) in email) = 0"
            + " and position(chr(2) in email) = 0"
            + " and position(chr(3) in email) = 0"
            + " and position(chr(4) in email) = 0"
            + " and position(chr(5) in email) = 0"
            + " and position(chr(6) in email) = 0"
            + " and position(chr(7) in email) = 0"
            + " and position(chr(8) in email) = 0"
            + " and position(chr(9) in email) = 0"
            + " and position(chr(10) in email) = 0"
            + " and position(chr(11) in email) = 0"
            + " and position(chr(12) in email) = 0"
            + " and position(chr(13) in email) = 0"
            + " and position(chr(14) in email) = 0"
            + " and position(chr(15) in email) = 0"
            + " and position(chr(16) in email) = 0"
            + " and position(chr(17) in email) = 0"
            + " and position(chr(18) in email) = 0"
            + " and position(chr(19) in email) = 0"
            + " and position(chr(20) in email) = 0"
            + " and position(chr(21) in email) = 0"
            + " and position(chr(22) in email) = 0"
            + " and position(chr(23) in email) = 0"
            + " and position(chr(24) in email) = 0"
            + " and position(chr(25) in email) = 0"
            + " and position(chr(26) in email) = 0"
            + " and position(chr(27) in email) = 0"
            + " and position(chr(28) in email) = 0"
            + " and position(chr(29) in email) = 0"
            + " and position(chr(30) in email) = 0"
            + " and position(chr(31) in email) = 0"
            + " and position(chr(32) in email) = 0"
            + " and position(chr(127) in email) = 0";
}
