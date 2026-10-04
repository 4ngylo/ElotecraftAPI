package me.angylo.elotecraftAPI.util;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe per-key cooldowns, e.g. {@code Cooldowns<UUID>} for players.
 */
// ponytail: expired entries are removed only when their key is read again; add a periodic sweep if keys are unbounded
public final class Cooldowns<K> {

    private final Map<K, Long> expiries = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    public Cooldowns() {
        this(System::nanoTime);
    }

    Cooldowns(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Starts the cooldown and returns {@code true} if {@code key} is not on cooldown.
     * Returns {@code false} and changes nothing if it still is.
     */
    public boolean tryUse(K key, Duration cooldown) {
        long now = nanoClock.getAsLong();
        boolean[] started = {false};
        expiries.compute(key, (ignored, expiry) -> {
            if (expiry != null && expiry - now > 0) {
                return expiry;
            }
            started[0] = true;
            return now + cooldown.toNanos();
        });
        return started[0];
    }

    /** Time left, or {@link Duration#ZERO} if not on cooldown. */
    public Duration remaining(K key) {
        Long expiry = expiries.get(key);
        if (expiry == null) {
            return Duration.ZERO;
        }
        long left = expiry - nanoClock.getAsLong();
        if (left <= 0) {
            expiries.remove(key, expiry);
            return Duration.ZERO;
        }
        return Duration.ofNanos(left);
    }

    public void clear(K key) {
        expiries.remove(key);
    }
}
