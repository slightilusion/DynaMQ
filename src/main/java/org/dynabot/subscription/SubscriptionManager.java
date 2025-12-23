package org.dynabot.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.redis.client.RedisAPI;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MQTT subscriptions with topic filter matching.
 * Supports + (single level) and # (multi level) wildcards.
 * Persists subscriptions to Redis for Admin API access.
 */
@Slf4j
public class SubscriptionManager {

    private static final String SUBSCRIPTION_KEY_PREFIX = "dynamq:subscriptions:";

    private final Vertx vertx;
    private final SubscriptionTree subscriptionTree;
    private final ConcurrentHashMap<String, Map<String, Integer>> clientSubscriptions = new ConcurrentHashMap<>();
    private RedisAPI redis;
    private ObjectMapper objectMapper = new ObjectMapper();

    public static SubscriptionManager create(Vertx vertx) {
        return new SubscriptionManager(vertx);
    }

    private SubscriptionManager(Vertx vertx) {
        this.vertx = vertx;
        this.subscriptionTree = new SubscriptionTree();
    }

    /**
     * Set Redis client for persistence (optional, if not set subscriptions are
     * local only)
     */
    public void setRedis(RedisAPI redis) {
        this.redis = redis;
        log.info("SubscriptionManager Redis persistence enabled");
    }

    /**
     * Add a subscription for a client
     */
    public void addSubscription(String clientId, String topicFilter, int qos) {
        subscriptionTree.addSubscription(clientId, topicFilter, qos);
        clientSubscriptions.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                .put(topicFilter, qos);
        log.debug("Added subscription: client={}, topic={}, qos={}", clientId, topicFilter, qos);

        saveToRedis(clientId);
    }

    /**
     * Remove a subscription for a client
     */
    public void removeSubscription(String clientId, String topicFilter) {
        subscriptionTree.removeSubscription(clientId, topicFilter);
        Map<String, Integer> topics = clientSubscriptions.get(clientId);
        if (topics != null) {
            topics.remove(topicFilter);
        }
        log.debug("Removed subscription: client={}, topic={}", clientId, topicFilter);

        saveToRedis(clientId);
    }

    /**
     * Remove all subscriptions for a client
     */
    public void removeAllSubscriptions(String clientId) {
        Map<String, Integer> topics = clientSubscriptions.remove(clientId);
        if (topics != null) {
            for (String topicFilter : topics.keySet()) {
                subscriptionTree.removeSubscription(clientId, topicFilter);
            }
            log.debug("Removed all subscriptions for client: {} ({} topics)", clientId, topics.size());
        }

        // Delete from Redis
        if (redis != null) {
            String redisKey = SUBSCRIPTION_KEY_PREFIX + clientId;
            redis.del(List.of(redisKey))
                    .onFailure(err -> log.error("Failed to delete subscriptions from Redis: {}", err.getMessage()));
        }
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
     * Get all subscriptions with QoS for a client
     */
    public Map<String, Integer> getClientSubscriptionsWithQos(String clientId) {
        return clientSubscriptions.getOrDefault(clientId, Collections.emptyMap());
    }

    /**
     * Check if a topic matches a filter
     */
    public static boolean topicMatches(String topicFilter, String topic) {
        return SubscriptionTree.topicMatches(topicFilter, topic);
    }

    /**
     * Save client subscriptions to Redis
     */
    private void saveToRedis(String clientId) {
        if (redis == null)
            return;

        String redisKey = SUBSCRIPTION_KEY_PREFIX + clientId;
        Map<String, Integer> subs = clientSubscriptions.get(clientId);

        if (subs == null || subs.isEmpty()) {
            redis.del(List.of(redisKey))
                    .onFailure(err -> log.error("Failed to delete subscriptions from Redis: {}", err.getMessage()));
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(subs);
            redis.set(List.of(redisKey, json))
                    .onFailure(err -> log.error("Failed to save subscriptions to Redis: {}", err.getMessage()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize subscriptions", e);
        }
    }
}
