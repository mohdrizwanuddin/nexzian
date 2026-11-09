package com.nexzian.security;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nexzian.exception.TooManyLoginAttemptsException;
import com.nexzian.util.EmailNormalizer;

/**
 * Brute-force protection for login.
 *
 * Two independent limits are applied and the STRICTER one wins:
 *  - per normalized email + client IP: 5 failed attempts / 15 minutes, then locked 15 minutes;
 *  - per client IP (all emails):       30 failed attempts / 15 minutes, then locked 15 minutes.
 *
 * Usage (see AuthService.login):
 *  1. {@link #beginAttempt} reserves an attempt BEFORE the password is checked. If either
 *     key is locked it throws {@link TooManyLoginAttemptsException}. The lock check does
 *     not depend on whether the email exists, so the response never reveals that.
 *  2. A wrong password or unknown email simply leaves the reserved attempt counted.
 *  3. {@link #credentialsVerified} is called when the password was correct (even if the
 *     account is then refused as inactive/suspended - that is not a failed guess). It
 *     clears the email+IP counter and returns only this request's own provisional slot to
 *     the IP counter; failures already recorded for the IP are NOT cleared, so logging in
 *     to one's own account cannot reset an IP-wide brute-force counter.
 *
 * Storage is in memory (see {@link AttemptStore} for the bounded-memory strategy and
 * limitations). The client IP comes from {@link ClientIp}: read its documentation about
 * running behind a reverse proxy.
 */
@Component
public class LoginAttemptLimiter {

    /** Handle for one reserved attempt. */
    public record Attempt(AttemptStore.Slot ipSlot, String pairKey) { }

    private final AttemptStore emailIpStore;
    private final AttemptStore ipStore;

    @Autowired
    public LoginAttemptLimiter(
            ObjectProvider<Clock> clockProvider,
            @Value("${app.security.login.max-failures-per-email-ip:5}") int maxFailuresPerEmailIp,
            @Value("${app.security.login.max-failures-per-ip:30}") int maxFailuresPerIp,
            @Value("${app.security.login.window-minutes:15}") long windowMinutes,
            @Value("${app.security.login.lock-minutes:15}") long lockMinutes,
            @Value("${app.security.max-tracked-keys:50000}") int maxTrackedKeys) {
        this(clockProvider.getIfAvailable(Clock::systemUTC),
                maxFailuresPerEmailIp, maxFailuresPerIp,
                Duration.ofMinutes(windowMinutes), Duration.ofMinutes(lockMinutes),
                maxTrackedKeys);
    }

    public LoginAttemptLimiter(
            Clock clock,
            int maxFailuresPerEmailIp,
            int maxFailuresPerIp,
            Duration window,
            Duration lock,
            int maxTrackedKeys) {

        // If the email+IP store is full of active entries, new pairs are not tracked
        // individually; the per-IP store still bounds them.
        this.emailIpStore = new AttemptStore(maxFailuresPerEmailIp, window, lock,
                maxTrackedKeys, AttemptStore.OverflowPolicy.SKIP_TRACKING, clock);

        // If the IP store is full of active entries, untracked IPs share one bucket.
        this.ipStore = new AttemptStore(maxFailuresPerIp, window, lock,
                maxTrackedKeys, AttemptStore.OverflowPolicy.SHARED_BUCKET, clock);
    }

    /** Trim + lowercase (Locale.ROOT): the same key for "Owner@X.com " and "owner@x.com". */
    public static String normalizeEmail(String email) {
        // Delegates to the platform-wide definition so the lockout key can never drift from the
        // way accounts are looked up; null becomes "" so it still forms a valid map key.
        String normalized = EmailNormalizer.normalize(email);
        return normalized == null ? "" : normalized;
    }

    /** Reserves an attempt, or throws if the email+IP or the IP is locked. */
    public Attempt beginAttempt(String normalizedEmail, String clientIp) {

        String pairKey = clientIp + "|" + normalizedEmail;

        AttemptStore.Slot ipSlot = ipStore.consume(clientIp);
        if (!ipSlot.allowed()) {
            long pairRemaining = emailIpStore.remainingLockMillis(pairKey);
            throw new TooManyLoginAttemptsException(
                    toSeconds(Math.max(ipSlot.retryAfterMillis(), pairRemaining)));
        }

        AttemptStore.Slot pairSlot = emailIpStore.consume(pairKey);
        if (!pairSlot.allowed()) {
            // This attempt is not processed, so it must not burn the IP-wide budget.
            ipStore.refund(ipSlot);
            throw new TooManyLoginAttemptsException(toSeconds(pairSlot.retryAfterMillis()));
        }

        return new Attempt(ipSlot, pairKey);
    }

    /** The password was correct: clear the email+IP counter, keep the IP-wide failures. */
    public void credentialsVerified(Attempt attempt) {
        ipStore.refund(attempt.ipSlot());
        emailIpStore.clear(attempt.pairKey());
    }

    /** Test support: forget all counters. */
    public void clearAll() {
        emailIpStore.clearAll();
        ipStore.clearAll();
    }

    private static long toSeconds(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }
}
