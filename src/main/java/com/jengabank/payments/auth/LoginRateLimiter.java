package com.jengabank.payments.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Max N login attempts per IP per minute, on top of per-account lockout. In memory, one instance. */
@Component
public class LoginRateLimiter {

    private static final long WINDOW_MS = 60_000;
    private static final int MAX_TRACKED_IPS = 10_000;

    private record Window(long start, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerMinute;

    public LoginRateLimiter(@Value("${jengabank.auth.login-rate-per-minute:10}") int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    public boolean allow(String ip) {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_IPS) {
            windows.clear(); // stops a memory-exhaustion attack with spoofed sources
        }
        Window w = windows.compute(ip, (k, old) ->
                (old == null || now - old.start() > WINDOW_MS) ? new Window(now, new AtomicInteger()) : old);
        return w.count().incrementAndGet() <= maxPerMinute;
    }
}
