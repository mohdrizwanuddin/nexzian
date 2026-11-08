package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nexzian.exception.TooManyLoginAttemptsException;
import com.nexzian.exception.TooManyRegistrationAttemptsException;
import com.nexzian.security.LoginAttemptLimiter;
import com.nexzian.security.RegistrationRateLimiter;

/** Unit tests (no Spring, fake clock) of the policy built on top of AttemptStore. */
class LoginAttemptLimiterTests {

    private static final String IP = "203.0.113.5";

    private MutableClock clock;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new LoginAttemptLimiter(clock, 5, 30,
                Duration.ofMinutes(15), Duration.ofMinutes(15), 1000);
    }

    /** One failed attempt = begin an attempt and never mark the credentials as verified. */
    private void fail(String email, String ip) {
        limiter.beginAttempt(LoginAttemptLimiter.normalizeEmail(email), ip);
    }

    // ---- email + IP limit ---------------------------------------------------------------

    @Test
    void fiveFailuresLockTheEmailAndIpPairForFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            fail("a@x.com", IP);
        }

        TooManyLoginAttemptsException locked = assertThrows(TooManyLoginAttemptsException.class,
                () -> fail("a@x.com", IP));

        assertEquals(15 * 60, locked.getRetryAfterSeconds());
        assertTrue(locked.getMessage().contains("15 minutes"), locked.getMessage());
    }

    @Test
    void lockEndsAfterFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            fail("a@x.com", IP);
        }
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertDoesNotThrow(() -> fail("a@x.com", IP));
    }

    @Test
    void lockedPairDoesNotLockOtherEmailsOrOtherIps() {
        for (int i = 0; i < 5; i++) {
            fail("a@x.com", IP);
        }

        assertDoesNotThrow(() -> fail("b@x.com", IP));
        assertDoesNotThrow(() -> fail("a@x.com", "198.51.100.9"));
    }

    @Test
    void emailIsNormalizedWithTrimAndLowercaseSoCasingVariantsShareOneCounter() {
        fail("Owner@Example.COM", IP);
        fail("  owner@example.com ", IP);
        fail("OWNER@EXAMPLE.COM", IP);
        fail("owner@example.com", IP);
        fail("Owner@example.com", IP);

        assertThrows(TooManyLoginAttemptsException.class, () -> fail("oWnEr@example.com", IP));
    }

    @Test
    void normalizationUsesLocaleRootNotTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); // upper-case I would become dotless
            assertEquals("info@x.com", LoginAttemptLimiter.normalizeEmail("  INFO@X.COM "));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ---- IP-wide limit ------------------------------------------------------------------------

    @Test
    void thirtyFailuresFromOneIpAcrossManyEmailsLockTheWholeIp() {
        for (int i = 0; i < 30; i++) {
            fail("user" + i + "@x.com", IP);
        }

        assertThrows(TooManyLoginAttemptsException.class, () -> fail("brand-new@x.com", IP));
        assertDoesNotThrow(() -> fail("brand-new@x.com", "198.51.100.9"));
    }

    @Test
    void whenBothLimitsApplyTheStricterRetryAfterIsReported() {
        // Pair lock starts now (+15 min).
        for (int i = 0; i < 5; i++) {
            fail("a@x.com", IP);
        }
        clock.advance(Duration.ofMinutes(10));
        // 25 more failures from other emails lock the IP at t+10 (+15 => t+25 min).
        for (int i = 0; i < 25; i++) {
            fail("other" + i + "@x.com", IP);
        }
        clock.advance(Duration.ofMinutes(1)); // now t+11

        TooManyLoginAttemptsException locked = assertThrows(TooManyLoginAttemptsException.class,
                () -> fail("a@x.com", IP));

        // Pair has 4 minutes left, IP has 14: the stricter (longer) one is reported.
        assertEquals(14 * 60, locked.getRetryAfterSeconds());
    }

    // ---- success handling -----------------------------------------------------------------------

    @Test
    void successfulLoginClearsTheEmailIpCounter() {
        for (int i = 0; i < 4; i++) {
            fail("a@x.com", IP);
        }
        limiter.credentialsVerified(
                limiter.beginAttempt(LoginAttemptLimiter.normalizeEmail("a@x.com"), IP));

        // A fresh set of 5 is available again; the 6th is blocked.
        for (int i = 0; i < 5; i++) {
            fail("a@x.com", IP);
        }
        assertThrows(TooManyLoginAttemptsException.class, () -> fail("a@x.com", IP));
    }

    @Test
    void successfulLoginDoesNotClearTheAggregateIpCounter() {
        for (int i = 0; i < 25; i++) {
            fail("other" + i + "@x.com", IP);
        }
        // A legitimate login from the same IP in the middle of an attack...
        limiter.credentialsVerified(
                limiter.beginAttempt(LoginAttemptLimiter.normalizeEmail("me@x.com"), IP));
        // ...must not reset the 25 failures already recorded for the IP:
        for (int i = 0; i < 5; i++) {
            fail("more" + i + "@x.com", IP);
        }

        assertThrows(TooManyLoginAttemptsException.class, () -> fail("anyone@x.com", IP));
    }

    @Test
    void attemptsBlockedByALockedPairDoNotBurnTheIpBudget() {
        for (int i = 0; i < 5; i++) {
            fail("victim@x.com", IP);
        }
        for (int i = 0; i < 100; i++) {
            assertThrows(TooManyLoginAttemptsException.class, () -> fail("victim@x.com", IP));
        }

        // The IP has only recorded the 5 real failures: 25 more are still allowed.
        for (int i = 0; i < 25; i++) {
            fail("other" + i + "@x.com", IP);
        }
        assertThrows(TooManyLoginAttemptsException.class, () -> fail("final@x.com", IP));
    }

    // ---- registration limiter ----------------------------------------------------------------------

    @Test
    void registrationAllowsFivePerIpPerHourThenBlocksForAnHour() {
        RegistrationRateLimiter registration =
                new RegistrationRateLimiter(clock, 5, Duration.ofMinutes(60), 1000);

        for (int i = 0; i < 5; i++) {
            registration.checkAndRecord(IP);
        }

        TooManyRegistrationAttemptsException blocked = assertThrows(
                TooManyRegistrationAttemptsException.class, () -> registration.checkAndRecord(IP));
        assertEquals(60 * 60, blocked.getRetryAfterSeconds());

        assertDoesNotThrow(() -> registration.checkAndRecord("198.51.100.9"));

        clock.advance(Duration.ofMinutes(60).plusSeconds(1));
        assertDoesNotThrow(() -> registration.checkAndRecord(IP));
    }

    @Test
    void registrationAndLoginCountersAreIndependent() {
        RegistrationRateLimiter registration =
                new RegistrationRateLimiter(clock, 5, Duration.ofMinutes(60), 1000);
        for (int i = 0; i < 5; i++) {
            registration.checkAndRecord(IP);
        }

        // Registering (even to the limit) leaves login counters untouched.
        for (int i = 0; i < 5; i++) {
            fail("a@x.com", IP);
        }
        assertThrows(TooManyLoginAttemptsException.class, () -> fail("a@x.com", IP));
        assertDoesNotThrow(() -> fail("b@x.com", IP));
    }
}
