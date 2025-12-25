package org.dynabot.cluster;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;
import org.dynabot.session.SessionManager;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node health monitor for cluster management.
 * Publishes heartbeats and tracks active nodes.
 * Handles node failure detection and client takeover.
 */
@Slf4j
@SuppressWarnings("unused") // config and sessionManager reserved for client takeover feature
public class NodeHealthMonitor {

    private static final String NODE_HEARTBEAT_KEY_PREFIX = "dynamq:node:";
    private static final String ACTIVE_NODES_KEY = "dynamq:nodes:active";
    private static final long HEARTBEAT_INTERVAL_MS = 5000; // 5 seconds
    private static final long NODE_TIMEOUT_SECONDS = 15; // 15 seconds

    private final Vertx vertx;
    private final RedisAPI redis;
    private final AppConfig config; // TODO: Use for client takeover configuration
    private final SessionManager sessionManager; // TODO: Use for client takeover on node failure
    private final String nodeId;

    private Long heartbeatTimerId;
    private Long checkTimerId;

    // Track known nodes and their last seen time
    private final ConcurrentHashMap<String, Instant> knownNodes = new ConcurrentHashMap<>();

    // Listeners for node events
    private final List<NodeEventListener> listeners = new ArrayList<>();

    public NodeHealthMonitor(Vertx vertx, AppConfig config, SessionManager sessionManager) {
        this.vertx = vertx;
        this.config = config;
        this.sessionManager = sessionManager;
        this.nodeId = config.getNodeName();

        // Create Redis client
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize());

        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        log.info("NodeHealthMonitor initialized for node: {}", nodeId);
    }

    /**
     * Start the health monitor
     */
    public void start() {
        // Register this node
        registerNode();

        // Start heartbeat timer
        heartbeatTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL_MS, id -> publishHeartbeat());

        // Start node check timer
        checkTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL_MS * 2, id -> checkNodes());

        log.info("Node health monitor started for node: {}", nodeId);
    }

    /**
     * Stop the health monitor
     */
    public void stop() {
        if (heartbeatTimerId != null) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = null;
        }
        if (checkTimerId != null) {
            vertx.cancelTimer(checkTimerId);
            checkTimerId = null;
        }

        // Unregister this node
        unregisterNode();

        log.info("Node health monitor stopped for node: {}", nodeId);
    }

    /**
     * Register this node in Redis
     */
    private void registerNode() {
        String nodeKey = NODE_HEARTBEAT_KEY_PREFIX + nodeId;
        long timestamp = Instant.now().toEpochMilli();

        // Set node heartbeat with TTL
        redis.setex(nodeKey, String.valueOf(NODE_TIMEOUT_SECONDS), String.valueOf(timestamp))
                .compose(v -> redis.sadd(List.of(ACTIVE_NODES_KEY, nodeId)))
                .onSuccess(v -> log.debug("Node registered: {}", nodeId))
                .onFailure(err -> log.error("Failed to register node", err));
    }

    /**
     * Unregister this node from Redis
     */
    private void unregisterNode() {
        String nodeKey = NODE_HEARTBEAT_KEY_PREFIX + nodeId;

        redis.del(List.of(nodeKey))
                .compose(v -> redis.srem(List.of(ACTIVE_NODES_KEY, nodeId)))
                .onSuccess(v -> log.debug("Node unregistered: {}", nodeId))
                .onFailure(err -> log.error("Failed to unregister node", err));
    }

    /**
     * Publish heartbeat to Redis along with memory metrics
     */
    private void publishHeartbeat() {
        String nodeKey = NODE_HEARTBEAT_KEY_PREFIX + nodeId;
        long timestamp = Instant.now().toEpochMilli();

        // Collect memory metrics
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        int usedPercent = (int) (usedMemory * 100 / maxMemory);

        // Store heartbeat timestamp
        redis.setex(nodeKey, String.valueOf(NODE_TIMEOUT_SECONDS), String.valueOf(timestamp))
                .onSuccess(v -> log.trace("Heartbeat published for node: {}", nodeId))
                .onFailure(err -> log.error("Failed to publish heartbeat", err));

        // Store node metrics in Redis Hash
        String metricsKey = "dynamq:node:metrics:" + nodeId;
        io.vertx.core.json.JsonObject metrics = new io.vertx.core.json.JsonObject()
                .put("used", usedMemory)
                .put("total", totalMemory)
                .put("max", maxMemory)
                .put("free", freeMemory)
                .put("usedPercent", usedPercent)
                .put("timestamp", timestamp);

        redis.setex(metricsKey, String.valueOf(NODE_TIMEOUT_SECONDS), metrics.encode())
                .onFailure(err -> log.error("Failed to store node metrics", err));
    }

    /**
     * Check all known nodes for failures
     */
    private void checkNodes() {
        // Get all registered nodes
        redis.smembers(ACTIVE_NODES_KEY)
                .onSuccess(response -> {
                    Set<String> registeredNodes = new HashSet<>();
                    if (response != null) {
                        for (int i = 0; i < response.size(); i++) {
                            registeredNodes.add(response.get(i).toString());
                        }
                    }

                    // Check each node's heartbeat
                    for (String node : registeredNodes) {
                        if (node.equals(nodeId)) {
                            continue; // Skip self
                        }

                        checkNodeHealth(node);
                    }
                })
                .onFailure(err -> log.error("Failed to get registered nodes", err));
    }

    /**
     * Check health of a specific node
     */
    private void checkNodeHealth(String targetNodeId) {
        String nodeKey = NODE_HEARTBEAT_KEY_PREFIX + targetNodeId;

        redis.get(nodeKey)
                .onSuccess(response -> {
                    if (response == null) {
                        // Node heartbeat expired, node is considered dead
                        handleNodeFailure(targetNodeId);
                    } else {
                        // Check if this is a new node before updating
                        boolean isNewNode = !knownNodes.containsKey(targetNodeId);

                        // Node is alive, update last seen time
                        knownNodes.put(targetNodeId, Instant.now());

                        if (isNewNode) {
                            // New node discovered
                            log.info("Node discovered: {}", targetNodeId);
                            notifyNodeJoined(targetNodeId);
                        }
                    }
                })
                .onFailure(err -> log.error("Failed to check node health: {}", targetNodeId, err));
    }

    /**
     * Handle node failure
     */
    private void handleNodeFailure(String failedNodeId) {
        log.warn("Node failure detected: {}", failedNodeId);

        // Remove from known nodes
        knownNodes.remove(failedNodeId);

        // Remove from active nodes set
        redis.srem(List.of(ACTIVE_NODES_KEY, failedNodeId))
                .onSuccess(v -> log.debug("Removed failed node from active set: {}", failedNodeId))
                .onFailure(err -> log.error("Failed to remove node from active set", err));

        // Notify listeners
        notifyNodeLeft(failedNodeId);

        // TODO: Implement client takeover
        // This would involve checking if any clients were connected to the failed node
        // and potentially reassigning them or notifying them to reconnect
    }

    /**
     * Get list of active nodes
     */
    public Future<Set<String>> getActiveNodes() {
        return redis.smembers(ACTIVE_NODES_KEY)
                .map(response -> {
                    Set<String> nodes = new HashSet<>();
                    if (response != null) {
                        for (int i = 0; i < response.size(); i++) {
                            nodes.add(response.get(i).toString());
                        }
                    }
                    return nodes;
                });
    }

    /**
     * Check if a specific node is alive
     */
    public Future<Boolean> isNodeAlive(String targetNodeId) {
        String nodeKey = NODE_HEARTBEAT_KEY_PREFIX + targetNodeId;
        return redis.exists(List.of(nodeKey))
                .map(response -> response != null && response.toInteger() > 0);
    }

    /**
     * Add a node event listener
     */
    public void addListener(NodeEventListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a node event listener
     */
    public void removeListener(NodeEventListener listener) {
        listeners.remove(listener);
    }

    private void notifyNodeJoined(String nodeId) {
        for (NodeEventListener listener : listeners) {
            try {
                listener.onNodeJoined(nodeId);
            } catch (Exception e) {
                log.error("Error notifying listener of node join", e);
            }
        }
    }

    private void notifyNodeLeft(String nodeId) {
        for (NodeEventListener listener : listeners) {
            try {
                listener.onNodeLeft(nodeId);
            } catch (Exception e) {
                log.error("Error notifying listener of node leave", e);
            }
        }
    }

    /**
     * Get the current node ID
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Interface for node event listeners
     */
    public interface NodeEventListener {
        void onNodeJoined(String nodeId);

        void onNodeLeft(String nodeId);
    }
}
