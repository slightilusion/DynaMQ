package org.dynabot.session;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.redis.client.*;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resilient Redis client with automatic reconnection.
 * Implements exponential backoff for reconnection attempts.
 */
@Slf4j
public class ResilientRedisClient {

    private static final int MAX_RECONNECT_RETRIES = 10;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;

    private final Vertx vertx;
    private final RedisOptions options;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private Redis redis;
    private RedisConnection connection;
    private RedisAPI api;

    public ResilientRedisClient(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize())
                .setMaxWaitingHandlers(config.getRedisMaxWaitingHandlers());

        connect();
    }

    /**
     * Get the Redis API for executing commands
     */
    public RedisAPI api() {
        return api;
    }

    /**
     * Check if Redis is connected
     */
    public boolean isConnected() {
        return api != null;
    }

    /**
     * Connect to Redis with retry logic
     */
    private void connect() {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }

        createRedisClient()
                .onSuccess(client -> {
                    redis = client;
                    api = RedisAPI.api(client);
                    reconnectAttempts.set(0);
                    connecting.set(false);
                    log.info("Redis connected successfully");

                    // Setup connection monitoring
                    client.connect().onComplete(ar -> {
                        if (ar.succeeded()) {
                            connection = ar.result();
                            connection.exceptionHandler(e -> {
                                log.error("Redis connection error: {}", e.getMessage());
                                scheduleReconnect();
                            });
                            connection.endHandler(v -> {
                                log.warn("Redis connection closed");
                                scheduleReconnect();
                            });
                        }
                    });
                })
                .onFailure(err -> {
                    connecting.set(false);
                    log.error("Redis connection failed: {}", err.getMessage());
                    scheduleReconnect();
                });
    }

    private Future<Redis> createRedisClient() {
        Promise<Redis> promise = Promise.promise();
        try {
            Redis client = Redis.createClient(vertx, options);
            promise.complete(client);
        } catch (Exception e) {
            promise.fail(e);
        }
        return promise.future();
    }

    /**
     * Schedule a reconnection attempt with exponential backoff
     */
    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();

        if (attempts > MAX_RECONNECT_RETRIES) {
            log.error("Redis: Max reconnection attempts ({}) exceeded", MAX_RECONNECT_RETRIES);
            return;
        }

        // Calculate backoff with jitter
        long backoff = Math.min(INITIAL_BACKOFF_MS * (1L << (attempts - 1)), MAX_BACKOFF_MS);
        long jitter = (long) (backoff * 0.2 * Math.random());
        long delay = backoff + jitter;

        log.info("Redis: Scheduling reconnect attempt {} in {}ms", attempts, delay);

        vertx.setTimer(delay, id -> {
            if (api == null) {
                connect();
            }
        });
    }

    /**
     * Close the Redis connection
     */
    public Future<Void> close() {
        if (connection != null) {
            connection.close();
        }
        if (redis != null) {
            redis.close();
        }
        api = null;
        return Future.succeededFuture();
    }

    /**
     * Get reconnection attempt count
     */
    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }
}
