package org.dynabot.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.micrometer.backends.BackendRegistries;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verticle that exposes Prometheus metrics endpoint.
 * Persists cumulative metrics to Redis for cluster-wide tracking and restart
 * resilience.
 */
@Slf4j
public class MetricsVerticle extends AbstractVerticle {

    private static final String METRICS_KEY_PREFIX = "dynamq:metrics:";
    private static final String CLUSTER_START_TIME_KEY = "dynamq:cluster:startTime";

    private HttpServer httpServer;
    private AppConfig appConfig;
    private static RedisAPI redis;
    private static long startTime = System.currentTimeMillis();

    // Cluster-wide rate calculation (from Redis)
    private static volatile long lastClusterReceived = 0;
    private static volatile long lastClusterSent = 0;
    private static volatile double clusterReceiveRate = 0;
    private static volatile double clusterSendRate = 0;

    // Connection metrics (activeConnections is ephemeral, not persisted)
    private static final AtomicLong activeConnections = new AtomicLong(0);

    // Cached values from Redis (loaded on startup, updated on increment)
    private static final AtomicLong totalConnections = new AtomicLong(0);
    private static final AtomicLong rejectedConnections = new AtomicLong(0);
    private static final AtomicLong messagesReceived = new AtomicLong(0);
    private static final AtomicLong messagesSent = new AtomicLong(0);
    private static final AtomicLong subscriptionCount = new AtomicLong(0);
    private static final AtomicLong messagesQos0 = new AtomicLong(0);
    private static final AtomicLong messagesQos1 = new AtomicLong(0);
    private static final AtomicLong messagesQos2 = new AtomicLong(0);
    private static final AtomicLong kafkaPublishSuccess = new AtomicLong(0);
    private static final AtomicLong kafkaPublishFailed = new AtomicLong(0);

    // Rate calculation (ephemeral)
    private static final AtomicLong lastMessagesReceived = new AtomicLong(0);
    private static final AtomicLong lastMessagesSent = new AtomicLong(0);
    private static volatile double receiveRate = 0;
    private static volatile double sendRate = 0;

    @Override
    public void start(Promise<Void> startPromise) {
        appConfig = new AppConfig(config());
        startTime = System.currentTimeMillis();

        // Initialize Redis if enabled
        if (appConfig.isRedisEnabled()) {
            initRedis();
        }

        // Get meter registry
        MeterRegistry registry = BackendRegistries.getDefaultNow();

        if (registry == null) {
            log.warn("No meter registry available, metrics disabled");
            startPromise.complete();
            return;
        }

        // Register custom metrics
        registerMetrics(registry);

        // Start rate calculator timer
        vertx.setPeriodic(5000, id -> calculateRates());

        // Create HTTP server for metrics endpoint
        Router router = Router.router(vertx);

        // Health check endpoint
        router.get("/health").handler(ctx -> {
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end("{\"status\":\"UP\"}");
        });

        // Prometheus metrics endpoint
        router.get(appConfig.getMetricsPath()).handler(ctx -> {
            if (registry instanceof PrometheusMeterRegistry) {
                String metrics = ((PrometheusMeterRegistry) registry).scrape();
                ctx.response()
                        .putHeader("Content-Type", "text/plain")
                        .end(metrics);
            } else {
                ctx.response()
                        .setStatusCode(503)
                        .end("Prometheus registry not available");
            }
        });

        // Ready check endpoint
        router.get("/ready").handler(ctx -> {
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end("{\"status\":\"READY\"}");
        });

        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router)
                .listen(appConfig.getMetricsPort())
                .onSuccess(server -> {
                    log.info("Metrics server started on port {}", server.actualPort());
                    startPromise.complete();
                })
                .onFailure(err -> {
                    log.error("Failed to start metrics server", err);
                    startPromise.fail(err);
                });
    }

    private void initRedis() {
        try {
            RedisOptions options = new RedisOptions()
                    .setConnectionString(appConfig.getRedisConnectionString());
            Redis.createClient(vertx, options)
                    .connect()
                    .onSuccess(conn -> {
                        redis = RedisAPI.api(conn);
                        log.info("MetricsVerticle connected to Redis - loading persisted metrics");
                        loadMetricsFromRedis();
                        registerClusterStartTime();
                    })
                    .onFailure(err -> log.warn("Failed to connect MetricsVerticle to Redis: {}", err.getMessage()));
        } catch (Exception e) {
            log.warn("Failed to initialize Redis for metrics: {}", e.getMessage());
        }
    }

    private void loadMetricsFromRedis() {
        // Load all persisted metrics from Redis
        loadMetric("totalConnections", totalConnections);
        loadMetric("rejectedConnections", rejectedConnections);
        loadMetric("messagesReceived", messagesReceived);
        loadMetric("messagesSent", messagesSent);
        loadMetric("messagesQos0", messagesQos0);
        loadMetric("messagesQos1", messagesQos1);
        loadMetric("messagesQos2", messagesQos2);
        loadMetric("kafkaPublishSuccess", kafkaPublishSuccess);
        loadMetric("kafkaPublishFailed", kafkaPublishFailed);
    }

    private void loadMetric(String name, AtomicLong target) {
        if (redis == null)
            return;
        redis.get(METRICS_KEY_PREFIX + name)
                .onSuccess(response -> {
                    if (response != null) {
                        try {
                            long value = Long.parseLong(response.toString());
                            target.set(value);
                            log.debug("Loaded metric {} = {}", name, value);
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                })
                .onFailure(err -> log.debug("Failed to load metric {}: {}", name, err.getMessage()));
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (httpServer != null) {
            httpServer.close()
                    .onComplete(ar -> {
                        log.info("Metrics server stopped");
                        stopPromise.complete();
                    });
        } else {
            stopPromise.complete();
        }
    }

    private void registerMetrics(MeterRegistry registry) {
        Gauge.builder("dynamq.connections.active", activeConnections, AtomicLong::get)
                .description("Current number of active MQTT connections")
                .register(registry);
        Gauge.builder("dynamq.connections.total", totalConnections, AtomicLong::get)
                .description("Total number of MQTT connections since startup")
                .register(registry);
        Gauge.builder("dynamq.connections.rejected", rejectedConnections, AtomicLong::get)
                .description("Total number of rejected connections")
                .register(registry);
        Gauge.builder("dynamq.messages.received.total", messagesReceived, AtomicLong::get)
                .description("Total number of messages received")
                .register(registry);
        Gauge.builder("dynamq.messages.sent.total", messagesSent, AtomicLong::get)
                .description("Total number of messages sent")
                .register(registry);
        Gauge.builder("dynamq.messages.qos0", messagesQos0, AtomicLong::get)
                .description("Messages with QoS 0").register(registry);
        Gauge.builder("dynamq.messages.qos1", messagesQos1, AtomicLong::get)
                .description("Messages with QoS 1").register(registry);
        Gauge.builder("dynamq.messages.qos2", messagesQos2, AtomicLong::get)
                .description("Messages with QoS 2").register(registry);
        Gauge.builder("dynamq.subscriptions.count", subscriptionCount, AtomicLong::get)
                .description("Current number of active subscriptions")
                .register(registry);
        Gauge.builder("dynamq.kafka.publish.success", kafkaPublishSuccess, AtomicLong::get)
                .description("Successful Kafka publishes").register(registry);
        Gauge.builder("dynamq.kafka.publish.failed", kafkaPublishFailed, AtomicLong::get)
                .description("Failed Kafka publishes").register(registry);

        log.info("Metrics registered");
    }

    private static void calculateRates() {
        // Local rates (for this node only)
        long currentReceived = messagesReceived.get();
        long currentSent = messagesSent.get();
        receiveRate = (currentReceived - lastMessagesReceived.get()) / 5.0;
        sendRate = (currentSent - lastMessagesSent.get()) / 5.0;
        lastMessagesReceived.set(currentReceived);
        lastMessagesSent.set(currentSent);

        // Cluster-wide rates (from Redis)
        if (redis != null) {
            redis.mget(List.of(
                    METRICS_KEY_PREFIX + "messagesReceived",
                    METRICS_KEY_PREFIX + "messagesSent")).onSuccess(response -> {
                        if (response != null && response.size() >= 2) {
                            long clusterReceived = parseRedisLong(response.get(0));
                            long clusterSent = parseRedisLong(response.get(1));
                            clusterReceiveRate = (clusterReceived - lastClusterReceived) / 5.0;
                            clusterSendRate = (clusterSent - lastClusterSent) / 5.0;
                            lastClusterReceived = clusterReceived;
                            lastClusterSent = clusterSent;
                        }
                    });
        }
    }

    private static long parseRedisLong(io.vertx.redis.client.Response response) {
        if (response == null)
            return 0;
        try {
            return Long.parseLong(response.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Register cluster start time in Redis using SETNX (only sets if not exists).
     * This ensures the earliest node's start time is preserved.
     */
    private void registerClusterStartTime() {
        if (redis == null)
            return;
        redis.setnx(CLUSTER_START_TIME_KEY, String.valueOf(startTime))
                .onSuccess(result -> {
                    if (result.toInteger() == 1) {
                        log.info("Registered cluster start time: {}", startTime);
                    } else {
                        log.debug("Cluster start time already set by another node");
                    }
                })
                .onFailure(err -> log.warn("Failed to register cluster start time: {}", err.getMessage()));
    }

    // Helper to increment and persist
    private static void incrementAndPersist(AtomicLong counter, String name) {
        counter.incrementAndGet();
        if (redis != null) {
            redis.incr(METRICS_KEY_PREFIX + name);
        }
    }

    private static void addAndPersist(AtomicLong counter, String name, long delta) {
        counter.addAndGet(delta);
        if (redis != null) {
            redis.incrby(METRICS_KEY_PREFIX + name, String.valueOf(delta));
        }
    }

    // Static methods for updating metrics (with Redis persistence)

    public static void incrementActiveConnections() {
        activeConnections.incrementAndGet();
        incrementAndPersist(totalConnections, "totalConnections");
    }

    public static void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public static void incrementRejectedConnections() {
        incrementAndPersist(rejectedConnections, "rejectedConnections");
    }

    public static void incrementMessagesReceived() {
        incrementAndPersist(messagesReceived, "messagesReceived");
    }

    public static void incrementMessagesReceived(int qos) {
        incrementAndPersist(messagesReceived, "messagesReceived");
        switch (qos) {
            case 0:
                incrementAndPersist(messagesQos0, "messagesQos0");
                break;
            case 1:
                incrementAndPersist(messagesQos1, "messagesQos1");
                break;
            case 2:
                incrementAndPersist(messagesQos2, "messagesQos2");
                break;
        }
    }

    public static void incrementMessagesSent() {
        incrementAndPersist(messagesSent, "messagesSent");
    }

    public static void incrementKafkaSuccess() {
        incrementAndPersist(kafkaPublishSuccess, "kafkaPublishSuccess");
    }

    public static void incrementKafkaFailed() {
        incrementAndPersist(kafkaPublishFailed, "kafkaPublishFailed");
    }

    public static void incrementSubscriptions(int count) {
        addAndPersist(subscriptionCount, "subscriptionCount", count);
    }

    public static void decrementSubscriptions(int count) {
        addAndPersist(subscriptionCount, "subscriptionCount", -count);
    }

    // Getters for API
    public static long getActiveConnections() {
        return activeConnections.get();
    }

    public static long getTotalConnections() {
        return totalConnections.get();
    }

    public static long getRejectedConnections() {
        return rejectedConnections.get();
    }

    public static long getMessagesReceived() {
        return messagesReceived.get();
    }

    public static long getMessagesSent() {
        return messagesSent.get();
    }

    public static long getSubscriptionCount() {
        return subscriptionCount.get();
    }

    public static long getMessagesQos0() {
        return messagesQos0.get();
    }

    public static long getMessagesQos1() {
        return messagesQos1.get();
    }

    public static long getMessagesQos2() {
        return messagesQos2.get();
    }

    public static long getKafkaPublishSuccess() {
        return kafkaPublishSuccess.get();
    }

    public static long getKafkaPublishFailed() {
        return kafkaPublishFailed.get();
    }

    public static double getReceiveRate() {
        return receiveRate;
    }

    public static double getSendRate() {
        return sendRate;
    }

    // Cluster-wide rate getters
    public static double getClusterReceiveRate() {
        return clusterReceiveRate;
    }

    public static double getClusterSendRate() {
        return clusterSendRate;
    }

    public static String getClusterStartTimeKey() {
        return CLUSTER_START_TIME_KEY;
    }

    public static long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
