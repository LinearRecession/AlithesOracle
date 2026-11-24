package io.alithes.oracle.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe time-based cooldown tracker for temporarily bypassing a failing upstream source.
 */
public final class CooldownGuard {

    private final Clock clock;
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>(Instant.EPOCH);

    public CooldownGuard(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public boolean isActive() {
        return clock.instant().isBefore(cooldownUntil.get());
    }

    public Instant activeUntil() {
        return cooldownUntil.get();
    }

    public void activate(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        cooldownUntil.set(clock.instant().plus(duration));
    }
}
