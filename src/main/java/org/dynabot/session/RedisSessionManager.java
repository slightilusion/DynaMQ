package org.dynabot.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.*;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-based distributed session manager.
 * Stores session data in Redis for cluster-wide access.
 */
@Slf4j
public class RedisSessionManager implements SessionManager {

    private static final String SESSION_KEY_PREFIX = "dynamq:session:";
    private static final String CONNECTION_KEY_PREFIX = "dynamq:connection:";
    private static final String KICK_CHANNEL = "dynamq:cluster:kick";

    private final Vertx vertx;
    private final AppConfig config;
    private final RedisAPI redis;
    private final ObjectMapper objectMapper;

    // Local cache for frequently accessed sessions
    private final ConcurrentHashMap<String, ClientSession> localCache = new ConcurrentHashMap<>();

    public RedisSessionManager(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.config = config;

        // Initialize ObjectMapper
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        // Create Redis client
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize())
                .setMaxWaitingHandlers(config.getRedisMaxWaitingHandlers());

        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        log.info("Redis session manager initialized: {}", config.getRedisConnectionString());
    }

    @Override
    public Future<ClientSession> createSession(String clientId, boolean cleanSession) {
        String sessionKey = SESSION_KEY_PREFIX + clientId;
        String connectionKey = CONNECTION_KEY_PREFIX + clientId;

        if (cleanSession) {
            // Clean session: delete existing and create new
            return redis.del(java.util.List.of(sessionKey, connectionKey))
                    .compose(v -> createNewSession(clientId, cleanSession));
        } else {
            // Try to restore existing session
            return redis.get(sessionKey)
                    .compose(response -> {
                        if (response != null) {
                            try {
                                ClientSession session = objectMapper.readValue(
                                        response.toString(), ClientSession.class);
                                session.setConnectedAt(Instant.now());
                                session.touch();
                                localCache.put(clientId, session);
                                log.debug("Restored session from Redis for: {}", clientId);
                                return Future.succeededFuture(session);
                            } catch (Exception e) {
                                log.warn("Failed to deserialize session, creating new: {}", clientId);
                                return createNewSession(clientId, cleanSession);
                            }
                        } else {
                            return createNewSession(clientId, cleanSession);
                        }
                    });
        }
    }

    private Future<ClientSession> createNewSession(String clientId, boolean cleanSession) {
        ClientSession session = ClientSession.builder()
                .clientId(clientId)
                .cleanSession(cleanSession)
                .connectedAt(Instant.now())
                .lastActivityAt(Instant.now())
                .nodeId(config.getNodeName())
                .subscriptions(new ConcurrentHashMap<>())
                .pendingQoS1(new ConcurrentHashMap<>())
                .pendingQoS2(new ConcurrentHashMap<>())
                .build();

        return saveSession(session)
                .map(v -> {
                    localCache.put(clientId, session);
                    log.debug("Created new session in Redis for: {}", clientId);
                    return session;
                });
    }

    private Future<Void> saveSession(ClientSession session) {
        String sessionKey = SESSION_KEY_PREFIX + session.getClientId();
        String connectionKey = CONNECTION_KEY_PREFIX + session.getClientId();

        try {
            String sessionJson = objectMapper.writeValueAsString(session);

            // Save session data (with expiry for clean sessions)
            int expiry = session.isCleanSession() ? 0 : config.getSessionExpiry();

            Future<Response> sessionFuture;
            if (expiry > 0) {
                sessionFuture = redis.setex(sessionKey, String.valueOf(expiry), sessionJson);
            } else {
                sessionFuture = redis.set(java.util.List.of(sessionKey, sessionJson));
            }

            // Save connection mapping (which node has this client)
            Future<Response> connectionFuture = redis.setex(
                    connectionKey,
                    String.valueOf(config.getKeepAliveMax() * 2),
                    config.getNodeName());

            return Future.all(sessionFuture, connectionFuture).mapEmpty();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Optional<ClientSession>> getSession(String clientId) {
        // Check local cache first
        ClientSession cached = localCache.get(clientId);
        if (cached != null) {
            return Future.succeededFuture(Optional.of(cached));
        }

        // Fetch from Redis
        String sessionKey = SESSION_KEY_PREFIX + clientId;
        return redis.get(sessionKey)
                .map(response -> {
                    if (response != null) {
                        try {
                            ClientSession session = objectMapper.readValue(
                                    response.toString(), ClientSession.class);
                            localCache.put(clientId, session);
                            return Optional.of(session);
                        } catch (Exception e) {
                            log.warn("Failed to deserialize session: {}", clientId, e);
                            return Optional.<ClientSession>empty();
                        }
                    }
                    return Optional.<ClientSession>empty();
                });
    }

    @Override
    public Future<Void> updateSession(ClientSession session) {
        localCache.put(session.getClientId(), session);
        return saveSession(session);
    }

    @Override
    public Future<Void> removeSession(String clientId, boolean permanent) {
        localCache.remove(clientId);

        String connectionKey = CONNECTION_KEY_PREFIX + clientId;

        if (permanent) {
            String sessionKey = SESSION_KEY_PREFIX + clientId;
            return redis.del(java.util.List.of(sessionKey, connectionKey)).mapEmpty();
        } else {
            return redis.del(java.util.List.of(connectionKey)).mapEmpty();
        }
    }

    @Override
    public Future<Boolean> isClientConnected(String clientId) {
        String connectionKey = CONNECTION_KEY_PREFIX + clientId;
        return redis.exists(java.util.List.of(connectionKey))
                .map(response -> response != null && response.toInteger() > 0);
    }

    @Override
    public Future<Optional<String>> getClientNode(String clientId) {
        String connectionKey = CONNECTION_KEY_PREFIX + clientId;
        return redis.get(connectionKey)
                .map(response -> {
                    if (response != null) {
                        return Optional.of(response.toString());
                    }
                    return Optional.<String>empty();
                });
    }

    @Override
    public Future<Void> forceDisconnect(String clientId) {
        // First check which node has this client
        return getClientNode(clientId)
                .compose(optNode -> {
                    if (optNode.isEmpty()) {
                        log.debug("Client {} not connected, nothing to disconnect", clientId);
                        return Future.succeededFuture();
                    }

                    String targetNode = optNode.get();
                    String currentNode = config.getNodeName();

                    if (currentNode.equals(targetNode)) {
                        // Client is on this node, disconnect locally
                        log.debug("Client {} is on this node, disconnecting locally", clientId);
                        vertx.eventBus().publish("mqtt.disconnect." + clientId, clientId);
                    } else {
                        // Client is on another node, use Redis PubSub to notify that node
                        log.info("Client {} is on node {}, sending cluster kick command", clientId, targetNode);
                        io.vertx.core.json.JsonObject kickCommand = new io.vertx.core.json.JsonObject()
                                .put("action", "kick")
                                .put("clientId", clientId)
                                .put("targetNode", targetNode)
                                .put("sourceNode", currentNode);

                        return redis.publish(KICK_CHANNEL, kickCommand.encode()).mapEmpty();
                    }

                    // Delete connection key to mark as disconnected immediately
                    String connectionKey = CONNECTION_KEY_PREFIX + clientId;
                    return redis.del(java.util.List.of(connectionKey)).mapEmpty();
                });
    }

    /**
     * Subscribe to kick commands from other nodes
     */
    public void subscribeToKickCommands() {
        String nodeName = config.getNodeName();

        // Create a separate Redis connection for PubSub
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString());

        Redis.createClient(vertx, options).connect()
                .onSuccess(conn -> {
                    conn.handler(message -> {
                        if (message != null && message.size() >= 3) {
                            String type = message.get(0).toString();

                            // Only process actual messages, not subscription confirmations
                            if (!"message".equals(type)) {
                                return;
                            }

                            String channel = message.get(1).toString();
                            String payload = message.get(2).toString();

                            if (KICK_CHANNEL.equals(channel)) {
                                try {
                                    io.vertx.core.json.JsonObject cmd = new io.vertx.core.json.JsonObject(payload);
                                    String targetNode = cmd.getString("targetNode");
                                    String clientId = cmd.getString("clientId");

                                    // Only process if this command is for this node
                                    if (nodeName.equals(targetNode)) {
                                        log.info("Received kick command for client {} from node {}",
                                                clientId, cmd.getString("sourceNode"));
                                        // Trigger local disconnect
                                        vertx.eventBus().publish("mqtt.disconnect." + clientId, clientId);
                                        // Remove from local cache
                                        localCache.remove(clientId);
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to process kick command: {}", e.getMessage());
                                }
                            }
                        }
                    });

                    // Subscribe to kick channel
                    conn.send(Request.cmd(Command.SUBSCRIBE).arg(KICK_CHANNEL))
                            .onSuccess(v -> log.info("Subscribed to kick channel: {}", KICK_CHANNEL))
                            .onFailure(err -> log.error("Failed to subscribe to kick channel", err));
                })
                .onFailure(err -> log.error("Failed to connect to Redis for kick subscription", err));
    }

    @Override
    public Future<Long> getSessionCount() {
        return redis.keys(SESSION_KEY_PREFIX + "*")
                .map(response -> {
                    if (response != null) {
                        return (long) response.size();
                    }
                    return 0L;
                });
    }
}
