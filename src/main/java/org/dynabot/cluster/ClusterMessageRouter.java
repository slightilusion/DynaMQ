package org.dynabot.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles message routing across cluster nodes.
 * Uses Redis PubSub for cross-node communication when nodes are on different
 * JVMs.
 * Falls back to Vert.x Event Bus for same-JVM communication.
 */
@Slf4j
public class ClusterMessageRouter {

    private static final String CLUSTER_PUBLISH_CHANNEL = "dynamq:cluster:publish";
    private static final String NODE_PUBLISH_PREFIX = "dynamq:node:";
    private static final String EVENT_BUS_PREFIX = "dynamq.node.";

    private final Vertx vertx;
    private final SessionManager sessionManager;
    private final AppConfig config;
    private final String nodeId;
    private final boolean clusterEnabled;
    private final boolean redisEnabled;

    private RedisAPI redis;
    private RedisConnection pubsubConnection;
    private ObjectMapper objectMapper;

    public ClusterMessageRouter(Vertx vertx, SessionManager sessionManager, AppConfig config) {
        this.vertx = vertx;
        this.sessionManager = sessionManager;
        this.config = config;
        this.nodeId = config.getNodeName();
        this.clusterEnabled = config.isClusterEnabled();
        this.redisEnabled = config.isRedisEnabled();

        if (clusterEnabled) {
            this.objectMapper = new ObjectMapper();
            registerEventBusHandlers();

            if (redisEnabled) {
                initializeRedis();
            }

            log.info("Cluster message router initialized for node: {} (redis={})", nodeId, redisEnabled);
        }
    }

    /**
     * Initialize Redis for PubSub
     */
    private void initializeRedis() {
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize());

        // Create Redis API client for publishing
        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        // Create separate connection for PubSub subscription
        Redis.createClient(vertx, options)
                .connect()
                .onSuccess(conn -> {
                    this.pubsubConnection = conn;
                    setupPubSubHandler(conn);
                    subscribeToChannels(conn);
                })
                .onFailure(err -> log.error("Failed to connect to Redis for PubSub", err));
    }

    /**
     * Setup PubSub message handler
     */
    private void setupPubSubHandler(RedisConnection conn) {
        conn.handler(message -> {
            if (message.size() >= 3) {
                String type = message.get(0).toString();
                if ("message".equals(type)) {
                    String channel = message.get(1).toString();
                    String payload = message.get(2).toString();
                    handleRedisPubSubMessage(channel, payload);
                }
            }
        });
    }

    /**
     * Subscribe to Redis channels
     */
    private void subscribeToChannels(RedisConnection conn) {
        String nodeChannel = NODE_PUBLISH_PREFIX + nodeId;

        RedisAPI.api(conn).subscribe(List.of(CLUSTER_PUBLISH_CHANNEL, nodeChannel))
                .onSuccess(v -> log.info("Subscribed to Redis channels: {}, {}", CLUSTER_PUBLISH_CHANNEL, nodeChannel))
                .onFailure(err -> log.error("Failed to subscribe to Redis channels", err));
    }

    /**
     * Handle incoming message from Redis PubSub
     */
    private void handleRedisPubSubMessage(String channel, String payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = objectMapper.readValue(payload, Map.class);

            String sourceNode = (String) message.get("sourceNode");

            // Skip messages from self
            if (nodeId.equals(sourceNode)) {
                return;
            }

            String clientId = (String) message.get("clientId");
            String topic = (String) message.get("topic");
            String payloadBase64 = (String) message.get("payload");
            byte[] messagePayload = payloadBase64 != null ? Base64.getDecoder().decode(payloadBase64) : new byte[0];
            int qos = message.get("qos") != null ? ((Number) message.get("qos")).intValue() : 0;
            boolean retain = message.get("retain") != null && (Boolean) message.get("retain");

            log.debug("Received Redis message: channel={}, clientId={}, topic={}", channel, clientId, topic);

            if (clientId != null) {
                // Targeted message
                deliverLocally(clientId, topic, messagePayload, qos, retain);
            } else if (topic != null) {
                // Broadcast message - deliver to all matching local subscribers
                // This is handled by the handlers that receive the message
                handleBroadcastMessage(topic, messagePayload, qos, retain, (String) message.get("excludeClientId"));
            }
        } catch (Exception e) {
            log.error("Failed to handle Redis PubSub message", e);
        }
    }

    /**
     * Handle broadcast message to local subscribers
     */
    private void handleBroadcastMessage(String topic, byte[] payload, int qos, boolean retain, String excludeClientId) {
        // This message is for local processing
        // The PublishHandler should handle routing to local subscribers
        vertx.eventBus().publish("mqtt.local.publish", new JsonObject()
                .put("topic", topic)
                .put("payload", payload)
                .put("qos", qos)
                .put("retain", retain)
                .put("excludeClientId", excludeClientId));
    }

    /**
     * Register Event Bus handlers for same-JVM communication
     */
    private void registerEventBusHandlers() {
        // Handle messages addressed to this specific node via Event Bus
        vertx.eventBus().<JsonObject>consumer(EVENT_BUS_PREFIX + nodeId, message -> {
            handleEventBusMessage(message.body());
        });

        log.debug("Registered Event Bus handler at: {}{}", EVENT_BUS_PREFIX, nodeId);
    }

    /**
     * Handle incoming message from Vert.x Event Bus
     */
    private void handleEventBusMessage(JsonObject message) {
        String clientId = message.getString("clientId");
        String topic = message.getString("topic");
        byte[] payload = message.getBinary("payload");
        int qos = message.getInteger("qos", 0);
        boolean retain = message.getBoolean("retain", false);

        log.debug("Received Event Bus message for client {}: topic={}", clientId, topic);

        // Deliver to local client if connected
        deliverLocally(clientId, topic, payload, qos, retain);
    }

    /**
     * Deliver message to a local client
     */
    private void deliverToClient(ClientSession session, String topic, byte[] payload, int qos, boolean retain) {
        int messageId = qos > 0 ? session.nextMessageId() : 0;

        session.getEndpoint().publish(
                topic,
                Buffer.buffer(payload),
                MqttQoS.valueOf(qos),
                false,
                retain,
                messageId);

        log.debug("Delivered message to {}: topic={}", session.getClientId(), topic);
    }

    /**
     * Route a message to a client, potentially on another node
     */
    public Future<Void> routeToClient(String clientId, String topic, byte[] payload, int qos, boolean retain) {
        if (!clusterEnabled) {
            return deliverLocally(clientId, topic, payload, qos, retain);
        }

        // Check if client is on this node
        return sessionManager.getClientNode(clientId)
                .compose(optNode -> {
                    if (optNode.isEmpty()) {
                        log.debug("Client {} not found in cluster", clientId);
                        return Future.succeededFuture();
                    }

                    String targetNode = optNode.get();
                    if (targetNode.equals(nodeId)) {
                        return deliverLocally(clientId, topic, payload, qos, retain);
                    } else {
                        return forwardToNode(targetNode, clientId, topic, payload, qos, retain);
                    }
                });
    }

    /**
     * Deliver message locally
     */
    private Future<Void> deliverLocally(String clientId, String topic, byte[] payload, int qos, boolean retain) {
        return sessionManager.getSession(clientId)
                .compose(optSession -> {
                    if (optSession.isPresent()) {
                        ClientSession session = optSession.get();
                        if (session.isConnected() && session.getEndpoint() != null) {
                            deliverToClient(session, topic, payload, qos, retain);
                        }
                    }
                    return Future.succeededFuture();
                });
    }

    /**
     * Forward message to another node
     */
    private Future<Void> forwardToNode(String targetNode, String clientId, String topic,
            byte[] payload, int qos, boolean retain) {
        if (redisEnabled && redis != null) {
            // Use Redis PubSub for cross-JVM communication
            return forwardViaRedis(targetNode, clientId, topic, payload, qos, retain);
        } else {
            // Use Vert.x Event Bus (only works in clustered Vert.x mode)
            return forwardViaEventBus(targetNode, clientId, topic, payload, qos, retain);
        }
    }

    /**
     * Forward via Redis PubSub
     */
    private Future<Void> forwardViaRedis(String targetNode, String clientId, String topic,
            byte[] payload, int qos, boolean retain) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("clientId", clientId);
            message.put("topic", topic);
            message.put("payload", Base64.getEncoder().encodeToString(payload));
            message.put("qos", qos);
            message.put("retain", retain);
            message.put("sourceNode", nodeId);

            String json = objectMapper.writeValueAsString(message);
            String channel = NODE_PUBLISH_PREFIX + targetNode;

            return redis.publish(channel, json)
                    .onSuccess(v -> log.debug("Published to Redis channel {}: clientId={}", channel, clientId))
                    .onFailure(err -> log.error("Failed to publish to Redis", err))
                    .mapEmpty();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Forward via Vert.x Event Bus
     */
    private Future<Void> forwardViaEventBus(String targetNode, String clientId, String topic,
            byte[] payload, int qos, boolean retain) {
        JsonObject message = new JsonObject()
                .put("clientId", clientId)
                .put("topic", topic)
                .put("payload", payload)
                .put("qos", qos)
                .put("retain", retain)
                .put("sourceNode", nodeId);

        DeliveryOptions options = new DeliveryOptions().setSendTimeout(5000);
        vertx.eventBus().publish(EVENT_BUS_PREFIX + targetNode, message, options);

        log.debug("Forwarded via Event Bus to node {}: clientId={}", targetNode, clientId);
        return Future.succeededFuture();
    }

    /**
     * Broadcast a message to all nodes (for topic-based routing)
     */
    public Future<Void> broadcastToCluster(String topic, byte[] payload, int qos, boolean retain,
            String excludeClientId) {
        if (!clusterEnabled) {
            return Future.succeededFuture();
        }

        if (redisEnabled && redis != null) {
            return broadcastViaRedis(topic, payload, qos, retain, excludeClientId);
        } else {
            broadcastViaEventBus(topic, payload, qos, retain, excludeClientId);
            return Future.succeededFuture();
        }
    }

    /**
     * Broadcast via Redis PubSub
     */
    private Future<Void> broadcastViaRedis(String topic, byte[] payload, int qos, boolean retain,
            String excludeClientId) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("topic", topic);
            message.put("payload", Base64.getEncoder().encodeToString(payload));
            message.put("qos", qos);
            message.put("retain", retain);
            message.put("excludeClientId", excludeClientId);
            message.put("sourceNode", nodeId);

            String json = objectMapper.writeValueAsString(message);

            return redis.publish(CLUSTER_PUBLISH_CHANNEL, json)
                    .onSuccess(v -> log.debug("Broadcast to cluster channel: topic={}", topic))
                    .onFailure(err -> log.error("Failed to broadcast to Redis", err))
                    .mapEmpty();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Broadcast via Event Bus
     */
    private void broadcastViaEventBus(String topic, byte[] payload, int qos, boolean retain, String excludeClientId) {
        JsonObject message = new JsonObject()
                .put("topic", topic)
                .put("payload", payload)
                .put("qos", qos)
                .put("retain", retain)
                .put("excludeClientId", excludeClientId)
                .put("sourceNode", nodeId);

        vertx.eventBus().publish("dynamq.cluster.broadcast", message);
        log.debug("Broadcast via Event Bus: topic={}", topic);
    }

    /**
     * Get current node ID
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Check if cluster mode is enabled
     */
    public boolean isClusterEnabled() {
        return clusterEnabled;
    }

    /**
     * Stop the router and cleanup resources
     */
    public void stop() {
        if (pubsubConnection != null) {
            pubsubConnection.close();
            pubsubConnection = null;
        }
        log.info("Cluster message router stopped");
    }
}
