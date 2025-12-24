package org.dynabot.routing;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.redis.client.*;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages data routing rules for MQTT to Kafka forwarding.
 * Routes are stored in Redis for persistence and cluster sharing.
 * Uses Redis PubSub for cross-node cache synchronization.
 */
@Slf4j
public class RouteManager {

    private static final String ROUTES_KEY = "dynamq:routes";
    private static final String ROUTES_SYNC_CHANNEL = "dynamq:routes:sync";

    private final Vertx vertx;
    private final AppConfig config;
    private final String nodeId;
    private final ConcurrentHashMap<String, DataRoute> routeCache = new ConcurrentHashMap<>();
    private RedisAPI redisAPI;
    private boolean redisEnabled;

    public RouteManager(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.nodeId = config.getNodeName();
        this.redisEnabled = config.isRedisEnabled();

        if (redisEnabled) {
            initRedis();
        }

        log.info("RouteManager initialized (redis={})", redisEnabled);
    }

    private void initRedis() {
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString());
        Redis redis = Redis.createClient(vertx, options);
        this.redisAPI = RedisAPI.api(redis);

        // Load routes from Redis on startup
        loadRoutes();

        // Subscribe to route sync channel
        subscribeToRouteSync();
    }

    /**
     * Subscribe to route sync notifications from other nodes
     */
    private void subscribeToRouteSync() {
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString());

        Redis.createClient(vertx, options).connect()
                .onSuccess(conn -> {
                    conn.handler(message -> {
                        if (message != null && message.size() >= 3) {
                            String type = message.get(0).toString();
                            if ("message".equals(type)) {
                                String payload = message.get(2).toString();
                                handleSyncMessage(payload);
                            }
                        }
                    });

                    RedisAPI.api(conn).subscribe(List.of(ROUTES_SYNC_CHANNEL))
                            .onSuccess(v -> log.info("Subscribed to route sync channel"))
                            .onFailure(err -> log.error("Failed to subscribe to route sync", err));
                })
                .onFailure(err -> log.error("Failed to connect for route sync", err));
    }

    /**
     * Handle route sync message from another node
     */
    private void handleSyncMessage(String payload) {
        try {
            io.vertx.core.json.JsonObject msg = new io.vertx.core.json.JsonObject(payload);
            String sourceNode = msg.getString("sourceNode");

            // Ignore messages from self
            if (nodeId.equals(sourceNode)) {
                return;
            }

            String action = msg.getString("action");
            log.debug("Received route sync: action={}, from={}", action, sourceNode);

            // Reload routes from Redis to get the latest data
            loadRoutes();
        } catch (Exception e) {
            log.warn("Failed to handle route sync message: {}", e.getMessage());
        }
    }

    /**
     * Publish route sync notification to other nodes
     */
    private void publishRouteSync(String action, String routeId) {
        if (!redisEnabled || redisAPI == null) {
            return;
        }

        io.vertx.core.json.JsonObject msg = new io.vertx.core.json.JsonObject()
                .put("action", action)
                .put("routeId", routeId)
                .put("sourceNode", nodeId)
                .put("timestamp", System.currentTimeMillis());

        redisAPI.publish(ROUTES_SYNC_CHANNEL, msg.encode())
                .onSuccess(v -> log.debug("Published route sync: action={}, routeId={}", action, routeId))
                .onFailure(err -> log.error("Failed to publish route sync", err));
    }

    /**
     * Load all routes from Redis into cache
     */
    public Future<Void> loadRoutes() {
        if (!redisEnabled || redisAPI == null) {
            return Future.succeededFuture();
        }

        return redisAPI.hgetall(ROUTES_KEY)
                .onSuccess(response -> {
                    routeCache.clear();
                    if (response != null && response.size() > 0) {
                        for (String key : response.getKeys()) {
                            try {
                                String json = response.get(key).toString();
                                DataRoute route = Json.decodeValue(json, DataRoute.class);
                                routeCache.put(route.getId(), route);
                            } catch (Exception e) {
                                log.warn("Failed to parse route {}: {}", key, e.getMessage());
                            }
                        }
                    }
                    log.info("Loaded {} routes from Redis", routeCache.size());
                })
                .onFailure(err -> log.error("Failed to load routes: {}", err.getMessage()))
                .mapEmpty();
    }

    /**
     * Get all routes
     */
    public List<DataRoute> getAllRoutes() {
        return new ArrayList<>(routeCache.values());
    }

    /**
     * Get a route by ID
     */
    public DataRoute getRoute(String id) {
        return routeCache.get(id);
    }

    /**
     * Add or update a route
     */
    public Future<DataRoute> saveRoute(DataRoute route) {
        if (route.getId() == null || route.getId().isEmpty()) {
            route.setId(DataRoute.generateId());
        }
        route.resetPattern(); // Reset compiled pattern

        routeCache.put(route.getId(), route);

        if (redisEnabled && redisAPI != null) {
            String json = Json.encode(route);
            return redisAPI.hset(Arrays.asList(ROUTES_KEY, route.getId(), json))
                    .map(r -> {
                        // Notify other nodes
                        publishRouteSync("save", route.getId());
                        return route;
                    })
                    .onFailure(err -> log.error("Failed to save route: {}", err.getMessage()));
        }

        return Future.succeededFuture(route);
    }

    /**
     * Delete a route
     */
    public Future<Boolean> deleteRoute(String id) {
        DataRoute removed = routeCache.remove(id);

        if (redisEnabled && redisAPI != null) {
            return redisAPI.hdel(Arrays.asList(ROUTES_KEY, id))
                    .map(r -> {
                        // Notify other nodes
                        publishRouteSync("delete", id);
                        return removed != null;
                    })
                    .onFailure(err -> log.error("Failed to delete route: {}", err.getMessage()));
        }

        return Future.succeededFuture(removed != null);
    }

    /**
     * Toggle route enabled status
     */
    public Future<DataRoute> toggleRoute(String id) {
        DataRoute route = routeCache.get(id);
        if (route == null) {
            return Future.failedFuture("Route not found: " + id);
        }

        route.setEnabled(!route.isEnabled());
        return saveRoute(route);
    }

    /**
     * Find matching routes for an MQTT topic
     * Returns the first matching enabled route, or null if none match
     */
    public DataRoute findMatchingRoute(String mqttTopic) {
        for (DataRoute route : routeCache.values()) {
            if (route.matches(mqttTopic)) {
                log.trace("Topic {} matched route {} -> {}",
                        mqttTopic, route.getId(), route.getKafkaTopic());
                return route;
            }
        }
        return null;
    }

    /**
     * Find all matching routes for an MQTT topic
     */
    public List<DataRoute> findAllMatchingRoutes(String mqttTopic) {
        return routeCache.values().stream()
                .filter(route -> route.matches(mqttTopic))
                .collect(Collectors.toList());
    }

    /**
     * Get route count
     */
    public int getRouteCount() {
        return routeCache.size();
    }

    /**
     * Get enabled route count
     */
    public int getEnabledRouteCount() {
        return (int) routeCache.values().stream()
                .filter(DataRoute::isEnabled)
                .count();
    }
}
