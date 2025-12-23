package org.dynabot.mqtt;

import io.vertx.core.Handler;
import io.vertx.mqtt.MqttEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final ConcurrentHashMap<String, AtomicInteger> connectionsPerIp = new ConcurrentHashMap<>();

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

        // Extract IP from address (format: "/ip:port" or "ip:port")
        String ip = extractIp(remoteAddress);

        // Check per-IP limit
        AtomicInteger ipCount = connectionsPerIp.computeIfAbsent(ip, k -> new AtomicInteger(0));
        if (ipCount.get() >= maxConnectionsPerIp) {
            log.warn("Rate limit: IP {} exceeded max connections ({})", ip, maxConnectionsPerIp);
            return false;
        }

        // Check overall rate limit
        long now = System.currentTimeMillis();
        long elapsed = now - lastResetTime.get();

        if (elapsed >= 1000) {
            // Reset counter every second
            if (lastResetTime.compareAndSet(lastResetTime.get(), now)) {
                connectionCount.set(0);
            }
        }

        if (connectionCount.get() >= connectRatePerSecond) {
            log.warn("Rate limit: Connection rate exceeded ({}/sec)", connectRatePerSecond);
            return false;
        }

        // Allow connection
        connectionCount.incrementAndGet();
        ipCount.incrementAndGet();
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
        AtomicInteger ipCount = connectionsPerIp.get(ip);
        if (ipCount != null) {
            int count = ipCount.decrementAndGet();
            if (count <= 0) {
                connectionsPerIp.remove(ip);
            }
        }
    }

    private String extractIp(String remoteAddress) {
        if (remoteAddress == null) {
            return "unknown";
        }
        // Remove leading "/" if present
        String addr = remoteAddress.startsWith("/") ? remoteAddress.substring(1) : remoteAddress;
        // Remove port
        int colonIndex = addr.lastIndexOf(':');
        if (colonIndex > 0) {
            return addr.substring(0, colonIndex);
        }
        return addr;
    }

    /**
     * Get current connection count for an IP
     */
    public int getConnectionCount(String ip) {
        AtomicInteger count = connectionsPerIp.get(extractIp(ip));
        return count != null ? count.get() : 0;
    }

    /**
     * Get total tracked IPs
     */
    public int getTrackedIpCount() {
        return connectionsPerIp.size();
    }
}
