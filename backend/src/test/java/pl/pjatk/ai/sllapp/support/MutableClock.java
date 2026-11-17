package pl.pjatk.ai.sllapp.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T12:00:00Z"));

    public void advance(Duration duration) {
        now.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId newZone) { return MutableClock.this.withZone(newZone); }
            @Override public Instant instant() { return now.get(); }
        };
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
