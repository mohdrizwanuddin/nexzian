package com.nexzian.security;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nexzian.exception.TooManyTeamMemberRequestsException;

/**
 * Limits POST /api/v1/users/team: 30 attempts per agency per hour (configurable).
 *
 * WHY: adding a team member answers "can this email be used?", which - however generic the
 * message - lets a signed-in owner test addresses against the whole platform. Unlimited, that is a
 * cheap account-enumeration tool. The limit makes it slow and visible while staying far above
 * what real onboarding needs (an agency adds employees a handful at a time).
 *
 * HOW IT COUNTS: every attempt that passed bean validation counts, whether it succeeded or failed
 * (duplicate email, invalid role, ...). The check happens BEFORE any email lookup, so a locked
 * agency learns nothing from further requests. Requests rejected by validation (HTTP 400) never
 * reach the controller and are not counted; neither are requests refused by authorization.
 *
 * KEY: the agency's tenant id, taken from the AUTHENTICATED user's database record by the
 * controller - never from the request body, a header or a client-supplied tenant id. The client
 * IP is deliberately not used: this endpoint is authenticated, so forwarded-IP headers (which a
 * client can forge) have no part in the decision. One agency's usage cannot affect another's.
 *
 * LIMITS OF THE DESIGN: the counters live in this JVM's memory (see {@link AttemptStore} for the
 * bounds and thread-safety). They are therefore PER APPLICATION INSTANCE and reset when the
 * application restarts; with N instances an agency could make up to N times the limit. Moving
 * to a shared store (database/Redis) is the upgrade path if the app is ever scaled out.
 */
@Component
public class TeamMemberCreationRateLimiter {

    private final AttemptStore store;

    @Autowired
    public TeamMemberCreationRateLimiter(
            ObjectProvider<Clock> clockProvider,
            @Value("${app.security.team-members.max-attempts-per-tenant:30}") int maxAttemptsPerTenant,
            @Value("${app.security.team-members.window-minutes:60}") long windowMinutes,
            @Value("${app.security.max-tracked-keys:50000}") int maxTrackedKeys) {
        this(clockProvider.getIfAvailable(Clock::systemUTC),
                maxAttemptsPerTenant, Duration.ofMinutes(windowMinutes), maxTrackedKeys);
    }

    /** Direct constructor for tests (injectable clock, small limits). */
    public TeamMemberCreationRateLimiter(
            Clock clock, int maxAttemptsPerTenant, Duration window, int maxTrackedKeys) {
        // SHARED_BUCKET: if more distinct agencies than maxTrackedKeys are tracked at once, the
        // overflow shares one bucket instead of becoming unlimited.
        this.store = new AttemptStore(maxAttemptsPerTenant, window, window,
                maxTrackedKeys, AttemptStore.OverflowPolicy.SHARED_BUCKET, clock);
    }

    /** Counts this attempt for the agency, or throws {@link TooManyTeamMemberRequestsException}. */
    public void checkAndRecord(UUID tenantId) {
        AttemptStore.Slot slot = store.consume(tenantId.toString());
        if (!slot.allowed()) {
            throw new TooManyTeamMemberRequestsException(
                    Math.max(1L, (slot.retryAfterMillis() + 999L) / 1000L));
        }
    }

    /** Test support: forget all counters. */
    public void clearAll() {
        store.clearAll();
    }
}
