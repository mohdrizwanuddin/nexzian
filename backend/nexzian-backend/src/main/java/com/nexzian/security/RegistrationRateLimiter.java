package com.nexzian.security;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nexzian.exception.TooManyRegistrationAttemptsException;

/**
 * Limits public agency registration: 5 attempts per client IP per hour.
 *
 * Every attempt that reaches the controller is counted (successful, duplicate, ...).
 * Requests rejected earlier by bean validation (HTTP 400) never reach the controller and
 * are therefore not counted. After the 5th attempt the IP is blocked for one hour.
 *
 * Independent of {@link LoginAttemptLimiter}: registering never touches login counters.
 * See {@link AttemptStore} for memory bounds and {@link ClientIp} for proxy requirements.
 */
@Component
public class RegistrationRateLimiter {

    private final AttemptStore store;

    @Autowired
    public RegistrationRateLimiter(
            ObjectProvider<Clock> clockProvider,
            @Value("${app.security.registration.max-attempts-per-ip:5}") int maxAttemptsPerIp,
            @Value("${app.security.registration.window-minutes:60}") long windowMinutes,
            @Value("${app.security.max-tracked-keys:50000}") int maxTrackedKeys) {
        this(clockProvider.getIfAvailable(Clock::systemUTC),
                maxAttemptsPerIp, Duration.ofMinutes(windowMinutes), maxTrackedKeys);
    }

    public RegistrationRateLimiter(
            Clock clock, int maxAttemptsPerIp, Duration window, int maxTrackedKeys) {
        this.store = new AttemptStore(maxAttemptsPerIp, window, window,
                maxTrackedKeys, AttemptStore.OverflowPolicy.SHARED_BUCKET, clock);
    }

    /** Counts this attempt, or throws {@link TooManyRegistrationAttemptsException}. */
    public void checkAndRecord(String clientIp) {
        AttemptStore.Slot slot = store.consume(clientIp);
        if (!slot.allowed()) {
            throw new TooManyRegistrationAttemptsException(
                    Math.max(1L, (slot.retryAfterMillis() + 999L) / 1000L));
        }
    }

    /** Test support: forget all counters. */
    public void clearAll() {
        store.clearAll();
    }
}
