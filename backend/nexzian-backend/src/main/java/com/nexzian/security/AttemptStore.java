package com.nexzian.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, bounded, in-memory attempt counter used for rate limiting.
 *
 * <h3>How it counts</h3>
 * Attempts are <b>reserved up front</b> with {@link #consume(String)}: the counter is
 * incremented atomically BEFORE the protected operation runs. When the count reaches
 * {@code maxAttempts} the key is locked for {@code lock}. Because the reservation is
 * atomic, a burst of simultaneous requests cannot slip past the limit (a
 * "check, then record afterwards" design would let every request in the burst pass
 * the check before the first failure was recorded). If the operation turns out to be
 * legitimate, the caller returns the slot with {@link #refund(Slot)}.
 *
 * <h3>Bounded memory without bypass</h3>
 * The store holds at most {@code maxEntries} keys. Expired entries are purged, but
 * <b>an active entry is never evicted</b>: evicting counters under pressure would let an
 * attacker reset their own limit by flooding the store with junk keys. When the store is
 * full of active entries and a NEW key arrives, the {@link OverflowPolicy} applies:
 * <ul>
 *   <li>{@code SKIP_TRACKING}: the new key is not tracked individually (used where a
 *       second, independent limiter - the per-IP one - still bounds the caller);</li>
 *   <li>{@code SHARED_BUCKET}: all untracked keys share ONE counter with the normal
 *       limits, so under a flood unknown clients share a single budget and cannot get
 *       unlimited attempts (fails closed rather than open).</li>
 * </ul>
 * Existing keys keep their own counters in both cases.
 *
 * <h3>Limitations</h3>
 * State lives in this JVM only: it is lost on restart and is not shared between several
 * server instances.
 */
public final class AttemptStore {

    public enum OverflowPolicy { SKIP_TRACKING, SHARED_BUCKET }

    /**
     * Result of {@link #consume(String)}. {@code chargedKey} is the exact key that was
     * charged (it may be the shared overflow bucket) and is null when nothing was tracked.
     */
    public record Slot(boolean allowed, String chargedKey, long windowStartMillis, long retryAfterMillis) {
        static Slot denied(long retryAfterMillis) {
            return new Slot(false, null, 0L, retryAfterMillis);
        }

        static Slot untracked() {
            return new Slot(true, null, 0L, 0L);
        }
    }

    private record Entry(int count, long windowStartMillis, long lockedUntilMillis) { }

    private static final String OVERFLOW_KEY = "\u0000overflow";
    private static final long PURGE_INTERVAL_MILLIS = 30_000L;
    private static final long FORCED_PURGE_INTERVAL_MILLIS = 1_000L;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong lastPurgeMillis = new AtomicLong(Long.MIN_VALUE / 2);

    private final int maxAttempts;
    private final long windowMillis;
    private final long lockMillis;
    private final int maxEntries;
    private final OverflowPolicy overflowPolicy;
    private final Clock clock;

    public AttemptStore(
            int maxAttempts,
            Duration window,
            Duration lock,
            int maxEntries,
            OverflowPolicy overflowPolicy,
            Clock clock) {

        if (maxAttempts < 1 || maxEntries < 1) {
            throw new IllegalArgumentException("maxAttempts and maxEntries must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.windowMillis = window.toMillis();
        this.lockMillis = lock.toMillis();
        this.maxEntries = maxEntries;
        this.overflowPolicy = overflowPolicy;
        this.clock = clock;
    }

    /**
     * Atomically reserves one attempt for {@code key}. If the key is currently locked the
     * result is not allowed and carries the remaining lock time.
     */
    public Slot consume(String key) {
        long now = clock.millis();
        purgeIfDue(now, PURGE_INTERVAL_MILLIS);

        String target = resolveTarget(key, now);
        if (target == null) {
            return Slot.untracked();
        }

        Slot[] result = new Slot[1];

        entries.compute(target, (k, old) -> {
            Entry current = (old == null || isExpired(old, now)) ? null : old;

            if (current != null && current.lockedUntilMillis() > now) {
                result[0] = Slot.denied(current.lockedUntilMillis() - now);
                return current;
            }

            boolean freshWindow = current == null
                    || now - current.windowStartMillis() >= windowMillis;
            int count = freshWindow ? 1 : current.count() + 1;
            long windowStart = freshWindow ? now : current.windowStartMillis();
            long lockedUntil = count >= maxAttempts ? now + lockMillis : 0L;

            result[0] = new Slot(true, k, windowStart, 0L);
            return new Entry(count, windowStart, lockedUntil);
        });

        return result[0];
    }

    /**
     * Returns a previously reserved slot (the attempt turned out to be legitimate, or was
     * not actually processed). Only affects the same counting window it was taken from.
     */
    public void refund(Slot slot) {
        if (slot == null || slot.chargedKey() == null) {
            return;
        }

        entries.compute(slot.chargedKey(), (k, existing) -> {
            if (existing == null || existing.windowStartMillis() != slot.windowStartMillis()) {
                return existing;
            }
            int count = existing.count() - 1;
            if (count <= 0) {
                return null;
            }
            // Dropping below the threshold lifts a lock that this slot helped cause.
            long lockedUntil = count < maxAttempts ? 0L : existing.lockedUntilMillis();
            return new Entry(count, existing.windowStartMillis(), lockedUntil);
        });
    }

    /** Remaining lock time for {@code key} in milliseconds (0 when not locked). Read-only. */
    public long remainingLockMillis(String key) {
        long now = clock.millis();
        Entry entry = entries.get(key);

        if (entry == null) {
            if (overflowPolicy == OverflowPolicy.SHARED_BUCKET && entries.size() >= maxEntries) {
                entry = entries.get(OVERFLOW_KEY);
            }
            if (entry == null) {
                return 0L;
            }
        }
        if (isExpired(entry, now)) {
            return 0L;
        }
        return Math.max(0L, entry.lockedUntilMillis() - now);
    }

    /** Forgets {@code key} completely (e.g. after a successful login). */
    public void clear(String key) {
        entries.remove(key);
    }

    /** Forgets everything. Intended for tests. */
    public void clearAll() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    // ---------------------------------------------------------------------------

    /** Existing key, or a new one if there is room, otherwise the overflow policy. */
    private String resolveTarget(String key, long now) {
        if (entries.containsKey(key) || entries.size() < maxEntries) {
            return key;
        }

        // Full: free space ONLY by dropping expired entries, never active ones.
        purgeIfDue(now, FORCED_PURGE_INTERVAL_MILLIS);

        if (entries.containsKey(key) || entries.size() < maxEntries) {
            return key;
        }
        return overflowPolicy == OverflowPolicy.SKIP_TRACKING ? null : OVERFLOW_KEY;
    }

    private boolean isExpired(Entry entry, long now) {
        if (entry.lockedUntilMillis() != 0L) {
            return entry.lockedUntilMillis() <= now;
        }
        return now - entry.windowStartMillis() >= windowMillis;
    }

    /** Removes expired entries at most once per interval (bounds the CPU cost of purging). */
    private void purgeIfDue(long now, long minIntervalMillis) {
        long last = lastPurgeMillis.get();
        if (now - last < minIntervalMillis || !lastPurgeMillis.compareAndSet(last, now)) {
            return;
        }
        entries.entrySet().removeIf(mapEntry -> isExpired(mapEntry.getValue(), now));
    }
}
