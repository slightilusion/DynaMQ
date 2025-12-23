package org.dynabot.subscription;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed distributed subscription manager.
 * Stores subscriptions in Redis for persistence and cluster synchronization.
 * Uses local SubscriptionTree for fast matching operations.
 */
@Slf4j
public class RedisSubscriptionManager {

    private static final String SUBSCRIPTION_KEY_PREFIX = "dynamq:subscriptions:";
    private static final String SUBSCRIPTION_CHANNEL = "dynamq:subscriptions:channel";

    private final Vertx vertx;
    private final RedisAPI redis;
    private final ObjectMapper objectMapper;
    private final AppConfig config;

    // Local subscription tree for fast matching
    private final SubscriptionTree subscriptionTree;

    // Client subscriptions cache: clientId -> Map<topicFilter, qos>
    private final ConcurrentHashMap<String, Map<String, Integer>> clientSubscriptions = new ConcurrentHashMap<>();

    public RedisSubscriptionManager(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.subscriptionTree = new SubscriptionTree();

        // Create Redis client
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize());

        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        // Subscribe to subscription updates from other nodes
        subscribeToUpdates();

        log.info("RedisSubscriptionManager initialized");
    }

    /**
     * Subscribe to subscription updates via Redis PubSub
     */
    private void subscribeToUpdates() {
        Redis.createClient(vertx, new RedisOptions()
                .setConnectionString(config.getRedisConnectionString()))
                .connect()
                .onSuccess(conn -> {
                    conn.handler(message -> {
                        if (message.size() >= 3) {
                            String type = message.get(0).toString();
                            if ("message".equals(type)) {
                                handleSubscriptionUpdate(message.get(2).toString());
                            }
                        }
                    });

                    RedisAPI.api(conn).subscribe(List.of(SUBSCRIPTION_CHANNEL))
                            .onSuccess(v -> log.info("Subscribed to subscription updates channel"))
                            .onFailure(err -> log.error("Failed to subscribe to updates channel", err));
                })
                .onFailure(err -> log.error("Failed to connect to Redis for PubSub", err));
    }

    /**
     * Handle subscription update from another node
     */
    private void handleSubscriptionUpdate(String message) {
        try {
            Map<String, Object> update = objectMapper.readValue(message, new TypeReference<>() {
            });
            String action = (String) update.get("action");
            String clientId = (String) update.get("clientId");
            String topicFilter = (String) update.get("topicFilter");
            Integer qos = (Integer) update.get("qos");
            String sourceNode = (String) update.get("sourceNode");

            // Skip if this update originated from this node
            if (config.getNodeName().equals(sourceNode)) {
                return;
            }

            log.debug("Received subscription update: action={}, client={}, topic={}", action, clientId, topicFilter);

            switch (action) {
                case "add" -> {
                    subscriptionTree.addSubscription(clientId, topicFilter, qos != null ? qos : 0);
                    clientSubscriptions.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                            .put(topicFilter, qos != null ? qos : 0);
                }
                case "remove" -> {
                    subscriptionTree.removeSubscription(clientId, topicFilter);
                    Map<String, Integer> subs = clientSubscriptions.get(clientId);
                    if (subs != null) {
                        subs.remove(topicFilter);
                    }
                }
                case "removeAll" -> {
                    Map<String, Integer> subs = clientSubscriptions.remove(clientId);
                    if (subs != null) {
                        for (String topic : subs.keySet()) {
                            subscriptionTree.removeSubscription(clientId, topic);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle subscription update", e);
        }
    }

    /**
     * Add a subscription for a client
     */
    public Future<Void> addSubscription(String clientId, String topicFilter, int qos) {
        // Update local tree
        subscriptionTree.addSubscription(clientId, topicFilter, qos);
        clientSubscriptions.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                .put(topicFilter, qos);

        log.debug("Added subscription: client={}, topic={}, qos={}", clientId, topicFilter, qos);

        // Persist to Redis
        return saveClientSubscriptions(clientId)
                .compose(v -> publishUpdate("add", clientId, topicFilter, qos));
    }

    /**
     * Remove a subscription for a client
     */
    public Future<Void> removeSubscription(String clientId, String topicFilter) {
        // Update local tree
        subscriptionTree.removeSubscription(clientId, topicFilter);
        Map<String, Integer> subs = clientSubscriptions.get(clientId);
        if (subs != null) {
            subs.remove(topicFilter);
        }

        log.debug("Removed subscription: client={}, topic={}", clientId, topicFilter);

        // Persist to Redis
        return saveClientSubscriptions(clientId)
                .compose(v -> publishUpdate("remove", clientId, topicFilter, null));
    }

    /**
     * Remove all subscriptions for a client
     */
    public Future<Void> removeAllSubscriptions(String clientId) {
        // Update local tree
        Map<String, Integer> subs = clientSubscriptions.remove(clientId);
        if (subs != null) {
            for (String topicFilter : subs.keySet()) {
                subscriptionTree.removeSubscription(clientId, topicFilter);
            }
            log.debug("Removed all subscriptions for client: {} ({} topics)", clientId, subs.size());
        }

        // Remove from Redis
        String redisKey = SUBSCRIPTION_KEY_PREFIX + clientId;
        return redis.del(List.of(redisKey))
                .compose(v -> publishUpdate("removeAll", clientId, null, null))
                .mapEmpty();
    }

    /**
     * Find all subscribers matching a topic
     */
    public Map<String, Integer> findMatchingSubscribers(String topic) {
        return subscriptionTree.match(topic);
    }

    /**
     * Get all subscriptions for a client
     */
    public Set<String> getClientSubscriptions(String clientId) {
        Map<String, Integer> subs = clientSubscriptions.get(clientId);
        return subs != null ? subs.keySet() : Collections.emptySet();
    }

    /**
     * Get all subscriptions for a client with QoS levels
     */
    public Map<String, Integer> getClientSubscriptionsWithQos(String clientId) {
        return clientSubscriptions.getOrDefault(clientId, Collections.emptyMap());
    }

    /**
     * Load subscriptions for a client from Redis
     */
    public Future<Map<String, Integer>> loadClientSubscriptions(String clientId) {
        String redisKey = SUBSCRIPTION_KEY_PREFIX + clientId;

        return redis.get(redisKey)
                .map(response -> {
                    if (response != null) {
                        try {
                            Map<String, Integer> subs = objectMapper.readValue(
                                    response.toString(),
                                    new TypeReference<>() {
                                    });

                            // Update local cache and tree
                            clientSubscriptions.put(clientId, new ConcurrentHashMap<>(subs));
                            for (Map.Entry<String, Integer> entry : subs.entrySet()) {
                                subscriptionTree.addSubscription(clientId, entry.getKey(), entry.getValue());
                            }

                            log.debug("Loaded {} subscriptions for client {} from Redis", subs.size(), clientId);
                            return subs;
                        } catch (Exception e) {
                            log.error("Failed to deserialize subscriptions for {}", clientId, e);
                            return Collections.<String, Integer>emptyMap();
                        }
                    }
                    return Collections.<String, Integer>emptyMap();
                });
    }

    /**
     * Save client subscriptions to Redis
     */
    private Future<Void> saveClientSubscriptions(String clientId) {
        String redisKey = SUBSCRIPTION_KEY_PREFIX + clientId;
        Map<String, Integer> subs = clientSubscriptions.get(clientId);

        if (subs == null || subs.isEmpty()) {
            return redis.del(List.of(redisKey)).mapEmpty();
        }

        try {
            String json = objectMapper.writeValueAsString(subs);
            return redis.set(List.of(redisKey, json)).mapEmpty();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Publish subscription update to other nodes
     */
    private Future<Void> publishUpdate(String action, String clientId, String topicFilter, Integer qos) {
        try {
            Map<String, Object> update = new HashMap<>();
            update.put("action", action);
            update.put("clientId", clientId);
            update.put("topicFilter", topicFilter);
            update.put("qos", qos);
            update.put("sourceNode", config.getNodeName());

            String message = objectMapper.writeValueAsString(update);
            return redis.publish(SUBSCRIPTION_CHANNEL, message).mapEmpty();
        } catch (Exception e) {
            log.error("Failed to publish subscription update", e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Check if a topic matches a filter
     */
    public static boolean topicMatches(String topicFilter, String topic) {
        return SubscriptionTree.topicMatches(topicFilter, topic);
    }

    /**
     * Stop the subscription manager and clean up resources
     */
    public void stop() {
        log.info("Stopping RedisSubscriptionManager");
        // Clear local caches
        subscriptionTree.clear();
        clientSubscriptions.clear();
    }
}
