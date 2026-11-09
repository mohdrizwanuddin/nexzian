package com.nexzian.util;

import java.util.Locale;

import com.nexzian.exception.UnsupportedEmailException;

/**
 * The single definition of a canonical, SUPPORTED email address in Nexzian.
 *
 * Login identifies a person globally by email, so "Owner@X.com", " owner@x.com" and
 * "OWNER@X.COM" must all mean the same account. Every place that WRITES an email (registration,
 * team members, platform admin bootstrap, entity setters) or LOOKS ONE UP (login, user-details
 * loading, duplicate checks) goes through this class, so the stored value and the value being
 * compared can never disagree about casing or padding.
 *
 * <h3>Normalization</h3>
 * Trim surrounding whitespace, then lowercase with {@link Locale#ROOT}. ROOT is deliberate: the
 * JVM default locale must never influence identity - under a Turkish default locale a plain
 * toLowerCase() turns "I" into the dotless i (U+0131).
 *
 * <h3>Supported-address policy (ASCII only)</h3>
 * A stored email must consist solely of printable ASCII characters (0x21-0x7E): no whitespace,
 * no control characters, nothing non-ASCII, at most {@value #MAX_LENGTH} characters.
 * Why: lowercase is only well defined and identical in Java and PostgreSQL for ASCII. For other
 * scripts the two disagree in edge cases (so a database CHECK could reject a value Java
 * produced), and Unicode adds identity problems that lowercasing cannot solve - the same visible
 * address in composed and decomposed form (NFC/NFD), a domain written as Unicode and as Punycode,
 * full-width letters, and cross-script look-alikes of someone else's address. Internationalized
 * domains must therefore be supplied in their ASCII (Punycode, "xn--...") form.
 *
 * The policy is enforced at three layers that use THIS definition: request validation (HTTP 400),
 * {@link #normalizeForStorage} (for internal callers that bypass validation), and a PostgreSQL
 * CHECK constraint (see docs/r4-email-integrity-migration.sql). Login deliberately uses plain
 * {@link #normalize}: an unsupported value just fails to match any account.
 */
public final class EmailNormalizer {

    /** Same as the users.email / tenants.email column length. */
    public static final int MAX_LENGTH = 150;

    /** Why a value is not a supported address; null from {@link #findProblem} means supported. */
    public enum Problem {
        NON_ASCII("Use an email address with ASCII characters only."),
        WHITESPACE_OR_CONTROL("Email address must not contain spaces or control characters."),
        TOO_LONG("Email address must not exceed " + MAX_LENGTH + " characters.");

        private final String message;

        Problem(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private EmailNormalizer() {
    }

    /**
     * Returns the canonical form of {@code email}, or {@code null} if it is null. Never rejects
     * anything - this is what login and lookups use.
     */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes {@code email} AND enforces the supported-address policy, for code that is about
     * to STORE the address. Throws {@link UnsupportedEmailException} (HTTP 400) if the normalized
     * value is not supported, so a caller that skipped request validation still cannot persist an
     * unsupported address. Null passes through (required-ness is a separate rule).
     */
    public static String normalizeForStorage(String email) {
        String normalized = normalize(email);
        Problem problem = findProblem(normalized);
        if (problem != null) {
            throw new UnsupportedEmailException(problem.message());
        }
        return normalized;
    }

    /**
     * Checks {@code value} against the supported-address policy as it is, WITHOUT trimming or
     * lowercasing (request validation judges what the client actually sent). Null and empty are
     * not judged here - "is it required?" belongs to @NotBlank.
     *
     * Non-ASCII is reported ahead of whitespace/control so that the documented message for
     * non-ASCII input is not hidden by a secondary problem.
     */
    public static Problem findProblem(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_LENGTH) {
            return Problem.TOO_LONG;
        }
        boolean whitespaceOrControl = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7F) {
                return Problem.NON_ASCII;
            }
            if (c < 0x21 || c == 0x7F) {
                whitespaceOrControl = true;
            }
        }
        return whitespaceOrControl ? Problem.WHITESPACE_OR_CONTROL : null;
    }
}
