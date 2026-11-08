package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nexzian.security.AttemptStore;
import com.nexzian.security.AttemptStore.OverflowPolicy;
import com.nexzian.security.AttemptStore.Slot;

/** Pure unit tests (no Spring) of the counting, expiry, overflow and concurrency rules. */
class AttemptStoreTests {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration LOCK = Duration.ofMinutes(10);

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
    }

    private AttemptStore store(int maxAttempts, int maxEntries, OverflowPolicy policy) {
        return new AttemptStore(maxAttempts, WINDOW, LOCK, maxEntries, policy, clock);
    }

    // ---- counting, locking, expiry ----------------------------------------------

    @Test
    void allowsExactlyMaxAttemptsThenLocksWithRetryAfter() {
        AttemptStore store = store(3, 100, OverflowPolicy.SHARED_BUCKET);

        assertTrue(store.consume("k").allowed());
        assertTrue(store.consume("k").allowed());
        assertTrue(store.consume("k").allowed()); // 3rd allowed, and it engages the lock

        Slot denied = store.consume("k");
        assertFalse(denied.allowed());
        assertEquals(LOCK.toMillis(), denied.retryAfterMillis());
    }

    @Test
    void retryAfterShrinksAsTimePasses() {
        AttemptStore store = store(1, 100, OverflowPolicy.SHARED_BUCKET);
        store.consume("k");

        clock.advance(Duration.ofMinutes(4));

        assertEquals(Duration.ofMinutes(6).toMillis(), store.consume("k").retryAfterMillis());
    }

    @Test
    void lockExpiresAndCountersStartAgain() {
        AttemptStore store = store(2, 100, OverflowPolicy.SHARED_BUCKET);
        store.consume("k");
        store.consume("k");
        assertFalse(store.consume("k").allowed());

        clock.advance(LOCK.plusSeconds(1));

        assertTrue(store.consume("k").allowed());
        assertTrue(store.consume("k").allowed());
        assertFalse(store.consume("k").allowed());
    }

    @Test
    void attemptsOutsideTheWindowDoNotAccumulate() {
        AttemptStore store = store(3, 100, OverflowPolicy.SHARED_BUCKET);
        store.consume("k");
        store.consume("k");

        clock.advance(WINDOW.plusSeconds(1)); // window over: counter starts again

        assertTrue(store.consume("k").allowed());
        assertTrue(store.consume("k").allowed());
        assertTrue(store.consume("k").allowed()); // would have been the 5th without the reset
        assertFalse(store.consume("k").allowed());
    }

    @Test
    void differentKeysAreIndependent() {
        AttemptStore store = store(1, 100, OverflowPolicy.SHARED_BUCKET);
        store.consume("a");

        assertFalse(store.consume("a").allowed());
        assertTrue(store.consume("b").allowed());
    }

    // ---- refund / clear -----------------------------------------------------------

    @Test
    void refundReturnsASlotAndLiftsALockItCaused() {
        AttemptStore store = store(3, 100, OverflowPolicy.SHARED_BUCKET);
        store.consume("k");
        store.consume("k");
        Slot third = store.consume("k"); // reaches the limit -> locked
        assertFalse(store.consume("k").allowed());

        store.refund(third);

        assertEquals(0L, store.remainingLockMillis("k"));
        assertTrue(store.consume("k").allowed());
    }

    @Test
    void refundIgnoresASlotFromAnEarlierWindow() {
        AttemptStore store = store(5, 100, OverflowPolicy.SHARED_BUCKET);
        Slot old = store.consume("k");

        clock.advance(WINDOW.plusSeconds(1));
        store.consume("k"); // new window, count 1
        store.consume("k"); // count 2
        store.refund(old);  // must NOT reduce the new window's count

        // 3 more allowed => count was still 2 (not 1): 2 + 3 = 5 and lock engages
        assertTrue(store.consume("k").allowed());
        assertTrue(store.consume("k").allowed());
        assertTrue(store.consume("k").allowed());
        assertFalse(store.consume("k").allowed());
    }

    @Test
    void clearForgetsTheKey() {
        AttemptStore store = store(1, 100, OverflowPolicy.SHARED_BUCKET);
        store.consume("k");
        assertFalse(store.consume("k").allowed());

        store.clear("k");

        assertTrue(store.consume("k").allowed());
    }

    // ---- concurrency ---------------------------------------------------------------

    @Test
    void simultaneousAttemptsCannotExceedTheLimit() throws Exception {
        AttemptStore store = store(5, 100, OverflowPolicy.SHARED_BUCKET);
        int threads = 64;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                start.await();
                return store.consume("same-key").allowed();
            }));
        }
        start.countDown();

        int allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                allowed++;
            }
        }
        executor.shutdown();

        assertEquals(5, allowed, "exactly the limit may pass, however many arrive at once");
    }

    // ---- bounded memory: active counters are never evicted ------------------------------

    @Test
    void fullStoreNeverEvictsActiveCountersAndSharesOneBucketForNewKeys() {
        AttemptStore store = store(3, 3, OverflowPolicy.SHARED_BUCKET);

        store.consume("a");
        store.consume("a");      // "a" has 2 of 3
        store.consume("b");
        store.consume("c");      // store is now full (3 keys)

        // New keys all land in ONE shared bucket with the normal limit (3).
        assertTrue(store.consume("new-1").allowed());
        assertTrue(store.consume("new-2").allowed());
        assertTrue(store.consume("new-3").allowed());
        assertFalse(store.consume("new-4").allowed(),
                "untracked keys must not get unlimited attempts");
        assertFalse(store.consume("new-5").allowed());

        // The pre-existing counter for "a" survived intact: one more attempt reaches 3.
        assertTrue(store.consume("a").allowed());
        assertFalse(store.consume("a").allowed());
    }

    @Test
    void skipTrackingPolicyLeavesNewKeysUntrackedButKeepsExistingOnesLimited() {
        AttemptStore store = store(2, 2, OverflowPolicy.SKIP_TRACKING);

        store.consume("a");
        store.consume("b"); // full

        for (int i = 0; i < 20; i++) {
            Slot slot = store.consume("untracked-" + i);
            assertTrue(slot.allowed());
            assertNull(slot.chargedKey());
        }
        assertEquals(2, store.size(), "no new entries are created while full");

        store.consume("a");
        assertFalse(store.consume("a").allowed(), "existing keys are still limited");
    }

    @Test
    void expiredEntriesAreRemovedToMakeRoomBeforeAnyOverflowRuleApplies() {
        AttemptStore store = store(3, 3, OverflowPolicy.SHARED_BUCKET);
        store.consume("a");
        store.consume("b");
        store.consume("c"); // full of active entries

        clock.advance(WINDOW.plusSeconds(5)); // they all expire

        Slot slot = store.consume("fresh");

        assertTrue(slot.allowed());
        assertEquals("fresh", slot.chargedKey(), "gets its own counter, not the shared bucket");
        assertEquals(1, store.size());
    }

    @Test
    void lockOnTheSharedBucketIsVisibleToReadOnlyChecks() {
        AttemptStore store = store(1, 1, OverflowPolicy.SHARED_BUCKET);
        store.consume("a"); // full
        store.consume("x"); // shared bucket, limit 1 -> locked

        assertTrue(store.remainingLockMillis("some-other-unseen-key") > 0);
    }
}
