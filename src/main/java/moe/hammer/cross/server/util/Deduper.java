package moe.hammer.cross.server.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Deduper {
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public Deduper(long ttlMillis) {
        this.ttlMillis = Math.max(1000, ttlMillis);
    }

    public boolean firstTime(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        purge(now);
        return seen.putIfAbsent(key, now) == null;
    }

    public void purge() {
        purge(System.currentTimeMillis());
    }

    private void purge(long now) {
        seen.entrySet().removeIf(entry -> now - entry.getValue() > ttlMillis);
    }
}
