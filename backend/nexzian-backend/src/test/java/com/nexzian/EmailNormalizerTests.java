package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.exception.UnsupportedEmailException;
import com.nexzian.util.EmailNormalizer;
import com.nexzian.util.EmailNormalizer.Problem;

/**
 * The canonical-email rule (trim + lowercase with Locale.ROOT) and the supported-address policy
 * (printable ASCII only, no whitespace/control characters, at most 150 characters) in isolation.
 * Everything that stores or looks up an email relies on this one definition, so its edge cases
 * are pinned here.
 */
class EmailNormalizerTests {

    // ---- normalization -------------------------------------------------------------------------------------

    @Test
    void trimsAndLowercases() {
        assertEquals("owner@x.com", EmailNormalizer.normalize("  Owner@X.COM \t"));
        assertEquals("owner@x.com", EmailNormalizer.normalize("owner@x.com"), "already canonical");
    }

    @Test
    void isIdempotentAndNullSafe() {
        String once = EmailNormalizer.normalize(" Mixed@Case.Example ");
        assertEquals(once, EmailNormalizer.normalize(once));
        assertNull(EmailNormalizer.normalize(null));
    }

    @Test
    void isNotAffectedByTheJvmDefaultLocale() {
        // Why: under a Turkish default locale a plain toLowerCase() maps "I" to the dotless i (U+0131),
        // so "INFO@EXAMPLE.COM" would be stored as "[dotless-i]nfo@example.com" and could never match
        // "info@example.com" - the same person would be locked out or get a second account.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("info@example.com", EmailNormalizer.normalize("INFO@EXAMPLE.COM"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void normalizeNeverRejectsAnythingSoLoginStaysCompatible() {
        // Login uses plain normalize(): an unsupported value must simply fail to match an account,
        // not throw (which would turn a login attempt into a 400/500 and reveal the policy).
        assertEquals("j\u00f6rg@example.com", EmailNormalizer.normalize("J\u00d6RG@example.com"));
        assertEquals("a b@x.com", EmailNormalizer.normalize("A B@x.com"));
    }

    @Test
    void entitiesNeverHoldANonCanonicalEmail() {
        // Why: defence in depth - even code that forgets to normalize cannot store a mixed-case
        // email through JPA, because the entity setters apply the same rule.
        User user = new User();
        user.setEmail("  Person@Agency.EXAMPLE ");
        assertEquals("person@agency.example", user.getEmail());

        Tenant tenant = new Tenant();
        tenant.setEmail(" Contact@Agency.EXAMPLE");
        assertEquals("contact@agency.example", tenant.getEmail());
    }

    // ---- supported-address policy ----------------------------------------------------------------------------

    @Test
    void ordinaryAndPunycodeAddressesAreSupported() {
        // Punycode is plain ASCII, so internationalized domains remain usable in their ASCII form.
        for (String ok : new String[] { "owner@example.com", "first.last+tag@sub.example.co.uk",
                "user@xn--bcher-kva.de", "o'brien@example.com", "a_b-c@example.org" }) {
            assertNull(EmailNormalizer.findProblem(ok), ok);
            assertEquals(ok.toLowerCase(Locale.ROOT), EmailNormalizer.normalizeForStorage(ok));
        }
    }

    @Test
    void nonAsciiIsRejectedInTheLocalPartAndInTheDomain() {
        // Why: Java and PostgreSQL only agree on lowercase for ASCII, and Unicode brings
        // composed/decomposed duplicates, Unicode-vs-Punycode duplicates and look-alike addresses.
        assertEquals(Problem.NON_ASCII, EmailNormalizer.findProblem("j\u00f6rg@example.com"));        // local part
        assertEquals(Problem.NON_ASCII, EmailNormalizer.findProblem("user@b\u00fccher.de"));          // Unicode domain
        assertEquals(Problem.NON_ASCII, EmailNormalizer.findProblem("\u7528\u6237@\u4f8b\u5b50.\u5e7f\u544a"));
        assertEquals(Problem.NON_ASCII, EmailNormalizer.findProblem("cafe\u0301@example.com"));       // combining mark
        assertEquals(Problem.NON_ASCII, EmailNormalizer.findProblem("\uFF21\uFF22@example.com"));     // full-width letters
        assertEquals(Problem.NON_ASCII, EmailNormalizer.findProblem("a\u00a0b@example.com"));         // no-break space
        assertEquals("Use an email address with ASCII characters only.", Problem.NON_ASCII.message());
    }

    @Test
    void whitespaceAndControlCharactersAreRejected() {
        for (String bad : new String[] { "a b@example.com", "a\tb@example.com", "a\nb@example.com",
                "a\rb@example.com", "a\u000bb@example.com", "a\fb@example.com", "a\u007fb@example.com",
                "a\u0000b@example.com", " leading@example.com", "trailing@example.com\n" }) {
            assertEquals(Problem.WHITESPACE_OR_CONTROL, EmailNormalizer.findProblem(bad),
                    "should be rejected: " + bad.replace("\n", "\\n").replace("\t", "\\t"));
        }
    }

    @Test
    void theLengthLimitIs150Characters() {
        String at150 = "a".repeat(150);
        String at151 = "a".repeat(151);
        assertNull(EmailNormalizer.findProblem(at150));
        assertEquals(Problem.TOO_LONG, EmailNormalizer.findProblem(at151));
        assertEquals(150, EmailNormalizer.MAX_LENGTH);
    }

    @Test
    void nullAndEmptyAreLeftToTheRequiredRule() {
        // "is it required?" is @NotBlank's job; the policy does not double-report it.
        assertNull(EmailNormalizer.findProblem(null));
        assertNull(EmailNormalizer.findProblem(""));
        assertNull(EmailNormalizer.normalizeForStorage(null));
    }

    @Test
    void normalizeForStorageEnforcesThePolicyForInternalCallers() {
        // Why: this is the safety net for code that never went through request validation.
        UnsupportedEmailException nonAscii = assertThrows(UnsupportedEmailException.class,
                () -> EmailNormalizer.normalizeForStorage("J\u00d6RG@example.com"));
        assertEquals(Problem.NON_ASCII.message(), nonAscii.getMessage());
        assertThrows(UnsupportedEmailException.class, () -> EmailNormalizer.normalizeForStorage("a b@example.com"));
        assertThrows(UnsupportedEmailException.class, () -> EmailNormalizer.normalizeForStorage("a".repeat(151)));

        // Surrounding whitespace is trimmed (normalization) BEFORE the policy is applied, exactly
        // like the stored value; internal whitespace is not "fixable" and is refused.
        assertEquals("padded@example.com", EmailNormalizer.normalizeForStorage("\t Padded@Example.com \n"));
    }

    @Test
    void entitySettersRefuseUnsupportedAddresses() {
        // Why: the third in-application layer - a bug or future code path that sets an unsupported
        // email directly on an entity fails here, before the database CHECK would have to.
        assertThrows(UnsupportedEmailException.class, () -> new User().setEmail("j\u00f6rg@example.com"));
        assertThrows(UnsupportedEmailException.class, () -> new Tenant().setEmail("a b@example.com"));
    }
}
