package org.dynabot.mqtt.handler;

import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.auth.AuthProvider;
import org.dynabot.config.AppConfig;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;
import org.dynabot.subscription.SubscriptionManager;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles MQTT CONNECT messages.
 */
@Slf4j
public class ConnectHandler {

    private final Vertx vertx;
    private final SessionManager sessionManager;
    private final SubscriptionManager subscriptionManager;
    private final AppConfig config;
    private final AuthProvider authProvider;

    public ConnectHandler(Vertx vertx, SessionManager sessionManager,
            SubscriptionManager subscriptionManager, AppConfig config, AuthProvider authProvider) {
        this.vertx = vertx;
        this.sessionManager = sessionManager;
        this.subscriptionManager = subscriptionManager;
        this.config = config;
        this.authProvider = authProvider;
    }

    /**
     * Handle a new MQTT connection
     * 
     * @param endpoint      The MQTT endpoint
     * @param localSessions Local session storage
     * @return Future containing the client session
     */
    public Future<ClientSession> handle(MqttEndpoint endpoint,
            ConcurrentHashMap<String, ClientSession> localSessions) {
        String clientId = endpoint.clientIdentifier();
        boolean cleanSession = endpoint.isCleanSession();

        // Extract auth info
        String username = endpoint.auth() != null ? endpoint.auth().getUsername() : null;
        String password = endpoint.auth() != null ? endpoint.auth().getPassword() : null;

        log.debug("CONNECT: clientId={}, cleanSession={}, username={}",
                clientId, cleanSession, username);

        // Validate client ID
        if (clientId == null || clientId.isEmpty()) {
            clientId = generateClientId();
            log.debug("Generated client ID: {}", clientId);
        }

        final String finalClientId = clientId;

        // Authenticate first
        return authProvider.authenticate(finalClientId, username, password)
                .compose(authResult -> {
                    if (!authResult.isSuccess()) {
                        log.warn("Authentication failed for {}: {}", finalClientId, authResult.getErrorMessage());
                        endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD);
                        return Future.failedFuture("Authentication failed: " + authResult.getErrorMessage());
                    }

                    log.debug("Authentication successful for {} as {}", finalClientId, authResult.getPrincipal());

                    // Check for existing connection
                    return sessionManager.isClientConnected(finalClientId)
                            .compose(isConnected -> {
                                if (isConnected) {
                                    log.info("Client {} already connected, disconnecting existing", finalClientId);
                                    return sessionManager.forceDisconnect(finalClientId)
                                            .compose(v -> createSession(endpoint, finalClientId, cleanSession,
                                                    authResult.getPrincipal(), localSessions));
                                } else {
                                    return createSession(endpoint, finalClientId, cleanSession,
                                            authResult.getPrincipal(), localSessions);
                                }
                            });
                });
    }

    private Future<ClientSession> createSession(MqttEndpoint endpoint, String clientId,
            boolean cleanSession, String username,
            ConcurrentHashMap<String, ClientSession> localSessions) {
        return sessionManager.createSession(clientId, cleanSession)
                .compose(session -> {
                    // Update session with connection details
                    session.setEndpoint(endpoint);
                    session.setConnectedAt(Instant.now());
                    session.setKeepAliveSeconds(endpoint.keepAliveTimeSeconds());
                    session.setUsername(username);

                    if (endpoint.auth() != null) {
                        session.setUsername(endpoint.auth().getUsername());
                    }

                    // Handle Will message
                    if (endpoint.will() != null && endpoint.will().isWillFlag()) {
                        session.setWillMessage(ClientSession.WillMessage.builder()
                                .topic(endpoint.will().getWillTopic())
                                .payload(endpoint.will().getWillMessageBytes())
                                .qos(endpoint.will().getWillQos())
                                .retain(endpoint.will().isWillRetain())
                                .build());
                    }

                    // Store in local sessions
                    localSessions.put(clientId, session);

                    // Accept connection BEFORE updateSession (in case updateSession is slow/fails)
                    boolean sessionPresent = !cleanSession && !session.getSubscriptions().isEmpty();
                    endpoint.accept(sessionPresent);

                    log.info("Client connected: {} (sessionPresent: {})", clientId, sessionPresent);

                    // If session was restored, re-register subscriptions
                    if (sessionPresent) {
                        session.getSubscriptions()
                                .forEach((topic, qos) -> subscriptionManager.addSubscription(clientId, topic, qos));
                    }

                    // Update session in background - don't fail the connection if this fails
                    sessionManager.updateSession(session)
                            .onFailure(err -> log.warn("Failed to update session in store for {}: {}", clientId,
                                    err.getMessage()));

                    return Future.succeededFuture(session);
                })
                .recover(err -> {
                    log.error("Failed to create session for {}: {}", clientId, err.getMessage());
                    try {
                        endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
                    } catch (IllegalStateException e) {
                        // Endpoint already closed, ignore
                        log.debug("Endpoint already closed for {}", clientId);
                    }
                    return Future.failedFuture(err);
                });
    }

    private String generateClientId() {
        return "dynamq-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
