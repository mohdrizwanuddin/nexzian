package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nexzian.exception.TooManyTeamMemberRequestsException;
import com.nexzian.security.TeamMemberCreationRateLimiter;

/**
 * Unit tests for the per-agency team-member creation limiter, using a movable clock so the window
 * is deterministic. They protect the account-enumeration defence: the endpoint that answers "can
 * this email be used?" must be limited, per agency, with a correct Retry-After, and must recover
 * once the window has passed.
 */
class TeamMemberCreationRateLimiterTests {

    private static final int MAX = 3;
    private static final Duration WINDOW = Duration.ofMinutes(60);

    private MutableClock clock;
    private TeamMemberCreationRateLimiter limiter;
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new TeamMemberCreationRateLimiter(clock, MAX, WINDOW, 1000);
    }

    @Test
    void allowsUpToTheThresholdThenBlocksWithARetryAfter() {
        for (int i = 0; i < MAX; i++) {
            assertDoesNotThrow(() -> limiter.checkAndRecord(tenantA));
        }

        TooManyTeamMemberRequestsException blocked = assertThrows(
                TooManyTeamMemberRequestsException.class, () -> limiter.checkAndRecord(tenantA));

        // Retry-After is how long until the window ends: here the whole hour, in seconds.
        assertEquals(WINDOW.toSeconds(), blocked.getRetryAfterSeconds());
    }

    @Test
    void retryAfterShrinksAsTimePasses() {
        for (int i = 0; i < MAX; i++) {
            limiter.checkAndRecord(tenantA);
        }
        clock.advance(Duration.ofMinutes(20));

        TooManyTeamMemberRequestsException blocked = assertThrows(
                TooManyTeamMemberRequestsException.class, () -> limiter.checkAndRecord(tenantA));

        assertEquals(Duration.ofMinutes(40).toSeconds(), blocked.getRetryAfterSeconds());
    }

    @Test
    void allowsAgainOnceTheWindowHasExpired() {
        for (int i = 0; i < MAX; i++) {
            limiter.checkAndRecord(tenantA);
        }
        assertThrows(TooManyTeamMemberRequestsException.class, () -> limiter.checkAndRecord(tenantA));

        clock.advance(WINDOW.plusSeconds(1));

        assertDoesNotThrow(() -> limiter.checkAndRecord(tenantA));
    }

    @Test
    void agenciesAreCountedIndependently() {
        // Why: one agency hammering the endpoint must not lock out another agency.
        for (int i = 0; i < MAX; i++) {
            limiter.checkAndRecord(tenantA);
        }
        assertThrows(TooManyTeamMemberRequestsException.class, () -> limiter.checkAndRecord(tenantA));

        for (int i = 0; i < MAX; i++) {
            assertDoesNotThrow(() -> limiter.checkAndRecord(tenantB));
        }
    }

    @Test
    void theMemoryBoundNeverTurnsIntoUnlimitedAttempts() {
        // Why: the counters are bounded. With room for only 1 tracked agency, further agencies
        // share one overflow bucket rather than escaping the limit entirely.
        TeamMemberCreationRateLimiter tiny =
                new TeamMemberCreationRateLimiter(clock, MAX, WINDOW, 1);
        tiny.checkAndRecord(UUID.randomUUID());   // occupies the only tracked slot

        int allowed = 0;
        for (int i = 0; i < MAX * 4; i++) {
            try {
                tiny.checkAndRecord(UUID.randomUUID());
                allowed++;
            } catch (TooManyTeamMemberRequestsException expected) {
                // the shared bucket filled up
            }
        }
        assertTrue(allowed <= MAX, "overflow agencies must share a bounded budget, but " + allowed + " got through");
    }

    @Test
    void concurrentAttemptsNeverExceedTheThreshold() throws Exception {
        // Why: the limiter is shared by all request threads; a check-then-act race would let more
        // than MAX attempts through.
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger allowed = new AtomicInteger();
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        limiter.checkAndRecord(tenantA);
                        allowed.incrementAndGet();
                    } catch (TooManyTeamMemberRequestsException blocked) {
                        // expected for the losers
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            assertEquals(MAX, allowed.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
