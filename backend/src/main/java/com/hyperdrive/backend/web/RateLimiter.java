package com.hyperdrive.backend.web;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window request counter with a hard cap on tracked keys, so memory stays bounded even under
 * a flood of distinct clients. Single-instance only: counters are not shared between backends.
 */
public class RateLimiter {

    private record Window(long startMillis, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long windowMillis;
    private final int maxTrackedKeys;

    public RateLimiter(Clock clock, Duration window, int maxTrackedKeys) {
        this.clock = clock;
        this.windowMillis = window.toMillis();
        this.maxTrackedKeys = maxTrackedKeys;
    }

    /** Returns true if the request is allowed, false if {@code key} exceeded {@code limit}. */
    public boolean tryAcquire(String key, int limit) {
        long now = clock.millis();
        if (!windows.containsKey(key) && windows.size() >= maxTrackedKeys) {
            windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
            if (windows.size() >= maxTrackedKeys) {
                return false; // Fail closed rather than grow without bound.
            }
        }
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || now - current.startMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(current.startMillis(), current.count() + 1);
        });
        return updated.count() <= limit;
    }

    public long retryAfterSeconds() {
        return Math.max(1, windowMillis / 1000);
    }

    int trackedKeys() {
        return windows.size();
    }
}
