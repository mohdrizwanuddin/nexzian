package com.nexzian;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A clock tests can move forward, so rate-limit windows and locks are deterministic. */
class MutableClock extends Clock {

    private final AtomicLong millis;

    MutableClock() {
        this(1_700_000_000_000L);
    }

    MutableClock(long startMillis) {
        this.millis = new AtomicLong(startMillis);
    }

    void advance(Duration duration) {
        millis.addAndGet(duration.toMillis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
        return millis.get();
    }
}
