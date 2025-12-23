package org.dynabot.routing;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages data routing rules for MQTT to Kafka forwarding.
 * Routes are stored in Redis for persistence and cluster sharing.
 */
@Slf4j
public class RouteManager {

    private static final String ROUTES_KEY = "dynamq:routes";

    private final Vertx vertx;
    private final AppConfig config;
    private final ConcurrentHashMap<String, DataRoute> routeCache = new ConcurrentHashMap<>();
    private RedisAPI redisAPI;
    private boolean redisEnabled;

    public RouteManager(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.config = config;
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
                    .map(r -> route)
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
                    .map(r -> removed != null)
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
