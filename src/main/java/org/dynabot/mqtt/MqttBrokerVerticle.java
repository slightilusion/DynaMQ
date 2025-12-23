package org.dynabot.mqtt;

import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttUnsubscribeMessage;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.acl.AclProvider;
import org.dynabot.auth.AuthProvider;
import org.dynabot.cluster.ClusterMessageRouter;
import org.dynabot.config.AppConfig;
import org.dynabot.mqtt.handler.ConnectHandler;
import org.dynabot.mqtt.handler.DisconnectHandler;
import org.dynabot.mqtt.handler.PublishHandler;
import org.dynabot.mqtt.handler.SubscribeHandler;
import org.dynabot.mqtt.retry.MessageRetryScheduler;
import org.dynabot.retain.RetainMessageStore;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;
import org.dynabot.subscription.SubscriptionManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core MQTT Broker Verticle.
 * Handles MQTT client connections and message routing.
 * Multiple instances are deployed to utilize all CPU cores.
 */
@Slf4j
public class MqttBrokerVerticle extends AbstractVerticle {

    private MqttServer mqttServer;
    private AppConfig appConfig;

    // Local session storage (per verticle instance)
    private final ConcurrentHashMap<String, ClientSession> localSessions = new ConcurrentHashMap<>();

    // Handlers
    private ConnectHandler connectHandler;
    private PublishHandler publishHandler;
    private SubscribeHandler subscribeHandler;
    private DisconnectHandler disconnectHandler;

    // Shared managers (accessed via Vert.x shared data or Event Bus)
    private SessionManager sessionManager;
    private SubscriptionManager subscriptionManager;
    private RetainMessageStore retainMessageStore;
    private MessageRetryScheduler retryScheduler;
    private AuthProvider authProvider;
    private ClusterMessageRouter clusterRouter;
    private AclProvider aclProvider;
    private ConnectionRateLimiter rateLimiter;

    // Metrics
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalMessagesReceived = new AtomicLong(0);
    private final AtomicLong totalMessagesSent = new AtomicLong(0);

    @Override
    public void start(Promise<Void> startPromise) {
        log.info("Starting MqttBrokerVerticle on thread: {}", Thread.currentThread().getName());

        // Load configuration
        appConfig = new AppConfig(config());

        // Initialize managers
        initializeManagers();

        // Initialize handlers
        initializeHandlers();

        // Start MQTT servers
        startMqttServers(startPromise);

        // Register on Event Bus for cross-verticle communication
        registerEventBusHandlers();
    }

    private void startMqttServers(Promise<Void> startPromise) {
        // Create plain MQTT server
        MqttServerOptions options = new MqttServerOptions()
                .setPort(appConfig.getServerPort())
                .setMaxMessageSize(appConfig.getMaxMessageSize())
                .setTimeoutOnConnect(10);

        mqttServer = MqttServer.create(vertx, options);
        mqttServer.endpointHandler(this::handleEndpoint);

        // Start plain MQTT server
        mqttServer.listen()
                .compose(server -> {
                    log.info("MQTT Server started on port {} (verticle: {})",
                            server.actualPort(), deploymentID());

                    // Chain SSL and WebSocket server startup
                    io.vertx.core.Future<Void> sslFuture = appConfig.isSslEnabled()
                            ? startSslServer()
                            : io.vertx.core.Future.succeededFuture();

                    return sslFuture.compose(v -> {
                        if (appConfig.isWebsocketEnabled()) {
                            return startWebSocketServer();
                        }
                        return io.vertx.core.Future.succeededFuture();
                    });
                })
                .onSuccess(v -> startPromise.complete())
                .onFailure(err -> {
                    log.error("Failed to start MQTT Server", err);
                    startPromise.fail(err);
                });
    }

    private io.vertx.core.Future<Void> startWebSocketServer() {
        try {
            MqttServerOptions wsOptions = new MqttServerOptions()
                    .setPort(appConfig.getWebsocketPort())
                    .setMaxMessageSize(appConfig.getMaxMessageSize())
                    .setTimeoutOnConnect(10)
                    .setUseWebSocket(true);

            MqttServer wsServer = MqttServer.create(vertx, wsOptions);
            wsServer.endpointHandler(this::handleEndpoint);

            return wsServer.listen()
                    .map(server -> {
                        log.info("MQTT WebSocket Server started on port {} (verticle: {})",
                                server.actualPort(), deploymentID());
                        return (Void) null;
                    });
        } catch (Exception e) {
            log.error("Failed to configure WebSocket server", e);
            return io.vertx.core.Future.failedFuture(e);
        }
    }

    private io.vertx.core.Future<Void> startSslServer() {
        try {
            MqttServerOptions sslOptions = new MqttServerOptions()
                    .setPort(appConfig.getSslPort())
                    .setMaxMessageSize(appConfig.getMaxMessageSize())
                    .setTimeoutOnConnect(10)
                    .setSsl(true)
                    .setPemKeyCertOptions(new io.vertx.core.net.PemKeyCertOptions()
                            .setCertPath(appConfig.getSslCertPath())
                            .setKeyPath(appConfig.getSslKeyPath()));

            // Configure client auth if required
            if ("required".equals(appConfig.getSslClientAuth())) {
                sslOptions.setClientAuth(io.vertx.core.http.ClientAuth.REQUIRED);
            } else if ("request".equals(appConfig.getSslClientAuth())) {
                sslOptions.setClientAuth(io.vertx.core.http.ClientAuth.REQUEST);
            }

            MqttServer sslServer = MqttServer.create(vertx, sslOptions);
            sslServer.endpointHandler(this::handleEndpoint);

            return sslServer.listen()
                    .map(server -> {
                        log.info("MQTT SSL Server started on port {} (verticle: {})",
                                server.actualPort(), deploymentID());
                        return (Void) null;
                    });
        } catch (Exception e) {
            log.error("Failed to configure SSL server", e);
            return io.vertx.core.Future.failedFuture(e);
        }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        log.info("Stopping MqttBrokerVerticle...");

        // Stop retry scheduler
        if (retryScheduler != null) {
            retryScheduler.stop();
        }

        // Close all client connections gracefully
        localSessions.values().forEach(session -> {
            if (session.getEndpoint() != null && session.getEndpoint().isConnected()) {
                session.getEndpoint().close();
            }
        });
        localSessions.clear();

        // Stop MQTT server
        if (mqttServer != null) {
            mqttServer.close()
                    .onComplete(ar -> {
                        log.info("MQTT Server stopped");
                        stopPromise.complete();
                    });
        } else {
            stopPromise.complete();
        }
    }

    private void initializeManagers() {
        // Initialize session manager
        sessionManager = SessionManager.create(vertx, appConfig);

        // Initialize subscription manager
        subscriptionManager = SubscriptionManager.create(vertx);

        // Connect subscription manager to Redis if enabled
        if (appConfig.isRedisEnabled()) {
            try {
                io.vertx.redis.client.RedisOptions options = new io.vertx.redis.client.RedisOptions()
                        .setConnectionString(appConfig.getRedisConnectionString());
                io.vertx.redis.client.Redis.createClient(vertx, options)
                        .connect()
                        .onSuccess(conn -> {
                            subscriptionManager.setRedis(io.vertx.redis.client.RedisAPI.api(conn));
                            log.info("SubscriptionManager connected to Redis for persistence");
                        })
                        .onFailure(err -> log.warn("Failed to connect SubscriptionManager to Redis: {}",
                                err.getMessage()));
            } catch (Exception e) {
                log.warn("Failed to setup Redis for SubscriptionManager: {}", e.getMessage());
            }
        }

        // Initialize retain message store
        retainMessageStore = RetainMessageStore.create(vertx, appConfig);

        // Initialize message retry scheduler
        retryScheduler = new MessageRetryScheduler(vertx, sessionManager, appConfig);
        retryScheduler.start();

        // Initialize auth provider
        authProvider = AuthProvider.create(vertx, appConfig);

        // Initialize cluster router
        clusterRouter = new ClusterMessageRouter(vertx, sessionManager, appConfig);

        // Initialize ACL provider
        aclProvider = AclProvider.create(vertx, appConfig);

        // Initialize rate limiter
        rateLimiter = new ConnectionRateLimiter(appConfig);

        // If using Redis session manager, subscribe to kick commands
        if (sessionManager instanceof org.dynabot.session.RedisSessionManager) {
            ((org.dynabot.session.RedisSessionManager) sessionManager).subscribeToKickCommands();
        }

        log.debug("Managers initialized");
    }

    private void initializeHandlers() {
        connectHandler = new ConnectHandler(vertx, sessionManager, subscriptionManager, appConfig, authProvider);
        publishHandler = new PublishHandler(vertx, sessionManager, subscriptionManager, appConfig, retainMessageStore,
                clusterRouter, aclProvider);
        subscribeHandler = new SubscribeHandler(vertx, sessionManager, subscriptionManager, retainMessageStore,
                aclProvider);
        disconnectHandler = new DisconnectHandler(vertx, sessionManager, subscriptionManager);

        log.debug("Handlers initialized");
    }

    private void handleEndpoint(MqttEndpoint endpoint) {
        String remoteAddr = endpoint.remoteAddress() != null ? endpoint.remoteAddress().toString() : "unknown";

        log.debug("New MQTT connection from: {}, clientId: {}",
                remoteAddr, endpoint.clientIdentifier());

        // Check rate limit before accepting connection
        if (!rateLimiter.allowConnection(remoteAddr)) {
            log.warn("Connection rejected due to rate limit: {} clientId={}",
                    remoteAddr, endpoint.clientIdentifier());
            try {
                endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
            } catch (IllegalStateException e) {
                log.debug("Endpoint already closed");
            }
            return;
        }

        // Let ConnectHandler handle accept - do NOT accept here
        connectHandler.handle(endpoint, localSessions)
                .onSuccess(session -> {
                    activeConnections.incrementAndGet();
                    org.dynabot.metrics.MetricsVerticle.incrementActiveConnections();
                    setupEndpointHandlers(endpoint, session);
                    log.info("Client connected: {} (active: {})",
                            endpoint.clientIdentifier(), activeConnections.get());
                })
                .onFailure(err -> {
                    log.warn("Client connection rejected: {} - {}",
                            endpoint.clientIdentifier(), err.getMessage());
                    try {
                        endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
                    } catch (IllegalStateException e) {
                        // Endpoint already accepted or closed, ignore
                        log.debug("Endpoint already closed for {}", endpoint.clientIdentifier());
                    }
                });
    }

    private void setupEndpointHandlers(MqttEndpoint endpoint, ClientSession session) {
        String clientId = session.getClientId();

        // Register Event Bus listener for force disconnect from cluster
        io.vertx.core.eventbus.MessageConsumer<Object> disconnectConsumer = vertx.eventBus()
                .consumer("mqtt.disconnect." + clientId, message -> {
                    log.info("Force disconnecting client {} due to duplicate connection on another node", clientId);
                    try {
                        if (endpoint.isConnected()) {
                            endpoint.close();
                        }
                    } catch (Exception e) {
                        log.warn("Error closing endpoint for {}", clientId, e);
                    }
                    localSessions.remove(clientId);
                    activeConnections.decrementAndGet();
                    org.dynabot.metrics.MetricsVerticle.decrementActiveConnections();
                });

        // PUBLISH handler
        endpoint.publishHandler(message -> handlePublish(endpoint, session, message));

        // SUBSCRIBE handler
        endpoint.subscribeHandler(message -> handleSubscribe(endpoint, session, message));

        // UNSUBSCRIBE handler
        endpoint.unsubscribeHandler(message -> handleUnsubscribe(endpoint, session, message));

        // PINGREQ handler
        endpoint.pingHandler(v -> {
            log.trace("PING from: {}", endpoint.clientIdentifier());
            // Vert.x automatically responds with PINGRESP
        });

        // QoS 2: PUBREL handler (client sends after receiving PUBREC)
        endpoint.publishReleaseHandler(messageId -> handlePubrel(endpoint, session, messageId));

        // QoS 2: PUBCOMP handler (client sends after receiving PUBREL)
        endpoint.publishCompletionHandler(messageId -> handlePubcomp(endpoint, session, messageId));

        // QoS 1: PUBACK handler (confirmation from client for QoS 1 messages we sent)
        endpoint.publishAcknowledgeHandler(messageId -> handlePuback(endpoint, session, messageId));

        // QoS 2: PUBREC handler (client received our QoS 2 message)
        endpoint.publishReceivedHandler(messageId -> handlePubrec(endpoint, session, messageId));

        // DISCONNECT handler
        endpoint.disconnectHandler(v -> {
            disconnectConsumer.unregister(); // Clean up Event Bus consumer
            handleDisconnect(endpoint, session, false);
        });

        // Connection close handler (abnormal disconnect)
        endpoint.closeHandler(v -> {
            disconnectConsumer.unregister(); // Clean up Event Bus consumer
            if (localSessions.containsKey(session.getClientId())) {
                handleDisconnect(endpoint, session, true);
            }
        });

        // Exception handler
        endpoint.exceptionHandler(err -> {
            log.error("Exception for client {}: {}", endpoint.clientIdentifier(), err.getMessage());
            handleDisconnect(endpoint, session, true);
        });
    }

    private void handlePublish(MqttEndpoint endpoint, ClientSession session, MqttPublishMessage message) {
        // Update metrics with QoS level
        org.dynabot.metrics.MetricsVerticle.incrementMessagesReceived(message.qosLevel().value());
        totalMessagesReceived.incrementAndGet();

        log.debug("PUBLISH from {}: topic={}, qos={}, payload size={}",
                endpoint.clientIdentifier(), message.topicName(),
                message.qosLevel(), message.payload().length());

        publishHandler.handle(endpoint, session, message)
                .onSuccess(v -> {
                    org.dynabot.metrics.MetricsVerticle.incrementMessagesSent();
                    totalMessagesSent.incrementAndGet();
                })
                .onFailure(err -> {
                    log.error("Failed to process PUBLISH from {}: {}",
                            endpoint.clientIdentifier(), err.getMessage());
                });
    }

    private void handleSubscribe(MqttEndpoint endpoint, ClientSession session, MqttSubscribeMessage message) {
        log.debug("SUBSCRIBE from {}: topics={}",
                endpoint.clientIdentifier(), message.topicSubscriptions());

        subscribeHandler.handle(endpoint, session, message)
                .onFailure(err -> {
                    log.error("Failed to process SUBSCRIBE from {}: {}",
                            endpoint.clientIdentifier(), err.getMessage());
                });
    }

    private void handleUnsubscribe(MqttEndpoint endpoint, ClientSession session, MqttUnsubscribeMessage message) {
        log.debug("UNSUBSCRIBE from {}: topics={}",
                endpoint.clientIdentifier(), message.topics());

        subscribeHandler.handleUnsubscribe(endpoint, session, message)
                .onFailure(err -> {
                    log.error("Failed to process UNSUBSCRIBE from {}: {}",
                            endpoint.clientIdentifier(), err.getMessage());
                });
    }

    private void handleDisconnect(MqttEndpoint endpoint, ClientSession session, boolean abnormal) {
        log.info("Client {} disconnected (abnormal: {})", endpoint.clientIdentifier(), abnormal);

        activeConnections.decrementAndGet();
        org.dynabot.metrics.MetricsVerticle.decrementActiveConnections();
        localSessions.remove(session.getClientId());

        disconnectHandler.handle(endpoint, session, abnormal)
                .onFailure(err -> {
                    log.error("Error handling disconnect for {}: {}",
                            endpoint.clientIdentifier(), err.getMessage());
                });
    }

    private void registerEventBusHandlers() {
        String address = "mqtt.broker." + deploymentID();

        // Handle messages from other verticles
        vertx.eventBus().<Buffer>consumer(address, message -> {
            // Process cross-verticle messages (e.g., route to local subscribers)
            log.trace("Received event bus message: {}", message.body());
        });

        // Broadcast address for all broker verticles
        vertx.eventBus().<Buffer>consumer("mqtt.broker.broadcast", message -> {
            // Handle broadcast messages (e.g., cluster-wide publish)
            log.trace("Received broadcast message: {}", message.body());
        });

        log.debug("Event bus handlers registered at: {}", address);
    }

    // Metrics accessors
    public long getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalMessagesReceived() {
        return totalMessagesReceived.get();
    }

    public long getTotalMessagesSent() {
        return totalMessagesSent.get();
    }

    // ========== QoS Handler Methods ==========

    /**
     * Handle PUBACK from client (confirmation of QoS 1 message we sent)
     */
    private void handlePuback(MqttEndpoint endpoint, ClientSession session, int messageId) {
        log.trace("PUBACK from {}: messageId={}", endpoint.clientIdentifier(), messageId);

        // Remove from pending QoS 1 messages
        ClientSession.PendingMessage removed = session.getPendingQoS1().remove(messageId);
        if (removed != null) {
            log.debug("QoS 1 message {} acknowledged by {}", messageId, session.getClientId());
        }
    }

    /**
     * Handle PUBREC from client (QoS 2 step 2: client received our message)
     * We need to send PUBREL in response
     */
    private void handlePubrec(MqttEndpoint endpoint, ClientSession session, int messageId) {
        log.trace("PUBREC from {}: messageId={}", endpoint.clientIdentifier(), messageId);

        // Move from pending QoS 2 outgoing to awaiting PUBCOMP
        ClientSession.PendingMessage pending = session.getPendingQoS2().get(messageId);
        if (pending != null) {
            // Send PUBREL (step 3)
            endpoint.publishRelease(messageId);
            log.debug("Sent PUBREL to {}: messageId={}", session.getClientId(), messageId);
        }
    }

    /**
     * Handle PUBREL from client (QoS 2 step 3: client releasing message after we
     * sent PUBREC)
     * We need to send PUBCOMP and process the message
     */
    private void handlePubrel(MqttEndpoint endpoint, ClientSession session, int messageId) {
        log.trace("PUBREL from {}: messageId={}", endpoint.clientIdentifier(), messageId);

        // Send PUBCOMP (step 4) - message delivery complete
        endpoint.publishComplete(messageId);

        // Remove any pending state for this message
        session.getPendingQoS2().remove(messageId);

        log.debug("QoS 2 delivery complete for {}: messageId={}", session.getClientId(), messageId);
    }

    /**
     * Handle PUBCOMP from client (QoS 2 step 4: final acknowledgment)
     */
    private void handlePubcomp(MqttEndpoint endpoint, ClientSession session, int messageId) {
        log.trace("PUBCOMP from {}: messageId={}", endpoint.clientIdentifier(), messageId);

        // Remove from pending QoS 2 messages - delivery complete
        ClientSession.PendingMessage removed = session.getPendingQoS2().remove(messageId);
        if (removed != null) {
            log.debug("QoS 2 message {} fully acknowledged by {}", messageId, session.getClientId());
        }
    }
}
