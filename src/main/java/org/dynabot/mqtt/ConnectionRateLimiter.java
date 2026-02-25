package org.dynabot.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection Rate Limiter.
 * Limits connections per IP and overall connection rate.
 */
@Slf4j
public class ConnectionRateLimiter {

    private final boolean enabled;
    private final int maxConnectionsPerIp;
    private final int connectRatePerSecond;

    // Track connections per IP
    private final ConcurrentHashMap<String, Integer> connectionsPerIp = new ConcurrentHashMap<>();

    // Track connection rate
    private final AtomicLong connectionCount = new AtomicLong(0);
    private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());

    public ConnectionRateLimiter(AppConfig config) {
        this.enabled = config.isRateLimitEnabled();
        this.maxConnectionsPerIp = config.getMaxConnectionsPerIp();
        this.connectRatePerSecond = config.getConnectRatePerSecond();

        if (enabled) {
            log.info("Connection rate limiting enabled: maxPerIp={}, ratePerSec={}",
                    maxConnectionsPerIp, connectRatePerSecond);
        }
    }

    /**
     * Check if a new connection should be allowed
     * 
     * @param remoteAddress Client IP address
     * @return true if connection is allowed, false if rate limited
     */
    public boolean allowConnection(String remoteAddress) {
        if (!enabled) {
            return true;
        }

        // Extract IP from address
        String ip = extractIp(remoteAddress);

        // Track per-IP limit using atomic compute to avoid race conditions
        int ipCount = connectionsPerIp.compute(ip, (k, v) -> v == null ? 1 : v + 1);
        if (ipCount > maxConnectionsPerIp) {
            log.warn("Rate limit: IP {} exceeded max connections ({})", ip, maxConnectionsPerIp);
            // Revert increment
            connectionsPerIp.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
            return false;
        }

        // Check overall rate limit with fixed race condition
        long now = System.currentTimeMillis();
        long expected = lastResetTime.get();

        if (now - expected >= 1000) {
            // Use CAS with the captured expected value to avoid race condition
            if (lastResetTime.compareAndSet(expected, now)) {
                connectionCount.set(0);
            }
        }

        if (connectionCount.get() >= connectRatePerSecond) {
            log.warn("Rate limit: Connection rate exceeded ({}/sec)", connectRatePerSecond);
            // Revert IP increment
            connectionsPerIp.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
            return false;
        }

        // Allow connection
        connectionCount.incrementAndGet();
        return true;
    }

    /**
     * Called when a connection is closed
     */
    public void connectionClosed(String remoteAddress) {
        if (!enabled) {
            return;
        }

        String ip = extractIp(remoteAddress);
        connectionsPerIp.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
    }

    private String extractIp(String remoteAddress) {
        if (remoteAddress == null) {
            return "unknown";
        }

        int start = 0;
        int len = remoteAddress.length();
        if (len > 0 && remoteAddress.charAt(0) == '/') {
            start = 1;
        }

        int colonIndex = remoteAddress.lastIndexOf(':');
        int end = (colonIndex > start) ? colonIndex : len;

        if (start == 0 && end == len) {
            return remoteAddress;
        }
        return remoteAddress.substring(start, end);
    }

    /**
     * Get current connection count for an IP
     */
    public int getConnectionCount(String ip) {
        Integer count = connectionsPerIp.get(extractIp(ip));
        return count != null ? count : 0;
    }

    /**
     * Get total tracked IPs
     */
    public int getTrackedIpCount() {
        return connectionsPerIp.size();
    }
}
