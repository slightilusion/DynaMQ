package org.dynabot.retain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.redis.client.*;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;
import org.dynabot.subscription.SubscriptionTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-based implementation of RetainMessageStore.
 * Stores retained messages in Redis for cluster-wide access.
 * Uses Redis PubSub for cross-node cache invalidation.
 * Key format: dynamq:retain:{topic}
 */
@Slf4j
public class RedisRetainMessageStore implements RetainMessageStore {

    private static final String RETAIN_KEY_PREFIX = "dynamq:retain:";
    private static final String RETAIN_SYNC_CHANNEL = "dynamq:retain:sync";

    private final Vertx vertx;
    private final RedisAPI redis;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    // Local cache for faster reads - high performance for million connections
    private final ConcurrentHashMap<String, RetainedMessage> localCache = new ConcurrentHashMap<>();

    public RedisRetainMessageStore(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.objectMapper = new ObjectMapper();
        this.nodeId = config.getNodeName();

        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize())
                .setMaxWaitingHandlers(config.getRedisMaxWaitingHandlers());

        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        // Subscribe to cache invalidation notifications
        subscribeToCacheSync(config);

        log.info("Redis retain message store initialized with cluster sync");
    }

    /**
     * Subscribe to cache sync notifications from other nodes
     */
    private void subscribeToCacheSync(AppConfig config) {
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString());

        Redis.createClient(vertx, options).connect()
                .onSuccess(conn -> {
                    conn.handler(message -> {
                        if (message != null && message.size() >= 3) {
                            String type = message.get(0).toString();
                            if ("message".equals(type)) {
                                handleSyncMessage(message.get(2).toString());
                            }
                        }
                    });

                    RedisAPI.api(conn).subscribe(List.of(RETAIN_SYNC_CHANNEL))
                            .onSuccess(v -> log.info("Subscribed to retain message sync channel"))
                            .onFailure(err -> log.error("Failed to subscribe to retain sync", err));
                })
                .onFailure(err -> log.error("Failed to connect for retain sync", err));
    }

    /**
     * Handle cache sync message - invalidate local cache
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
            String topic = msg.getString("topic");

            log.debug("Received retain sync: action={}, topic={}, from={}", action, topic, sourceNode);

            // Invalidate local cache
            if ("remove".equals(action) || "store".equals(action)) {
                localCache.remove(topic);
            }
        } catch (Exception e) {
            log.warn("Failed to handle retain sync message: {}", e.getMessage());
        }
    }

    /**
     * Publish cache invalidation notification
     */
    private void publishCacheSync(String action, String topic) {
        io.vertx.core.json.JsonObject msg = new io.vertx.core.json.JsonObject()
                .put("action", action)
                .put("topic", topic)
                .put("sourceNode", nodeId)
                .put("timestamp", System.currentTimeMillis());

        redis.publish(RETAIN_SYNC_CHANNEL, msg.encode())
                .onFailure(err -> log.error("Failed to publish retain sync", err));
    }

    @Override
    public Future<Void> store(String topic, Buffer payload, int qos) {
        if (payload == null || payload.length() == 0) {
            return remove(topic);
        }

        String key = RETAIN_KEY_PREFIX + topic;

        RetainedMessage message = RetainedMessage.builder()
                .topic(topic)
                .payload(payload.getBytes())
                .qos(qos)
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            String json = objectMapper.writeValueAsString(message);
            return redis.set(List.of(key, json))
                    .onSuccess(v -> {
                        localCache.put(topic, message);
                        // Notify other nodes to invalidate their cache
                        publishCacheSync("store", topic);
                        log.debug("Stored retain message in Redis for topic: {}", topic);
                    })
                    .onFailure(err -> log.error("Failed to store retain message: {}", err.getMessage()))
                    .mapEmpty();
        } catch (Exception e) {
            log.error("Failed to serialize retain message", e);
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Optional<RetainedMessage>> get(String topic) {
        // Check local cache first for high performance
        RetainedMessage cached = localCache.get(topic);
        if (cached != null) {
            return Future.succeededFuture(Optional.of(cached));
        }

        String key = RETAIN_KEY_PREFIX + topic;
        return redis.get(key)
                .map(response -> {
                    if (response != null) {
                        try {
                            RetainedMessage message = objectMapper.readValue(
                                    response.toString(), RetainedMessage.class);
                            localCache.put(topic, message);
                            return Optional.of(message);
                        } catch (Exception e) {
                            log.warn("Failed to deserialize retain message: {}", e.getMessage());
                            return Optional.<RetainedMessage>empty();
                        }
                    }
                    return Optional.<RetainedMessage>empty();
                });
    }

    @Override
    public Future<Void> remove(String topic) {
        String key = RETAIN_KEY_PREFIX + topic;
        localCache.remove(topic);

        return redis.del(List.of(key))
                .onSuccess(v -> {
                    // Notify other nodes to invalidate their cache
                    publishCacheSync("remove", topic);
                    log.debug("Removed retain message from Redis for topic: {}", topic);
                })
                .onFailure(err -> log.error("Failed to remove retain message: {}", err.getMessage()))
                .mapEmpty();
    }

    @Override
    public Future<List<RetainedMessage>> getMatching(String topicFilter) {
        // For efficiency, we use Redis SCAN with pattern matching
        // However, MQTT wildcards don't map directly to Redis patterns
        // So we need to fetch all and filter

        return redis.keys(RETAIN_KEY_PREFIX + "*")
                .compose(keysResponse -> {
                    if (keysResponse == null || keysResponse.size() == 0) {
                        return Future.succeededFuture(new ArrayList<RetainedMessage>());
                    }

                    List<String> keys = new ArrayList<>();
                    for (Response key : keysResponse) {
                        keys.add(key.toString());
                    }

                    // MGET all retain messages
                    return redis.mget(keys)
                            .map(response -> {
                                List<RetainedMessage> result = new ArrayList<>();

                                for (int i = 0; i < response.size(); i++) {
                                    Response value = response.get(i);
                                    if (value != null) {
                                        try {
                                            RetainedMessage message = objectMapper.readValue(
                                                    value.toString(), RetainedMessage.class);

                                            // Check if topic matches filter
                                            if (SubscriptionTree.topicMatches(topicFilter, message.getTopic())) {
                                                result.add(message);
                                            }
                                        } catch (Exception e) {
                                            log.warn("Failed to deserialize retain message", e);
                                        }
                                    }
                                }

                                log.debug("Found {} retain messages matching filter: {}",
                                        result.size(), topicFilter);
                                return result;
                            });
                });
    }
}
