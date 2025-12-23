package org.dynabot.mqtt.handler;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;
import org.dynabot.subscription.SubscriptionManager;

/**
 * Handles MQTT disconnect (graceful and abnormal).
 */
@Slf4j
public class DisconnectHandler {

    private final Vertx vertx;
    private final SessionManager sessionManager;
    private final SubscriptionManager subscriptionManager;

    public DisconnectHandler(Vertx vertx, SessionManager sessionManager,
            SubscriptionManager subscriptionManager) {
        this.vertx = vertx;
        this.sessionManager = sessionManager;
        this.subscriptionManager = subscriptionManager;
    }

    /**
     * Handle client disconnect
     * 
     * @param endpoint The MQTT endpoint
     * @param session  The client session
     * @param abnormal Whether this is an abnormal disconnect (connection lost)
     */
    public Future<Void> handle(MqttEndpoint endpoint, ClientSession session, boolean abnormal) {
        String clientId = session.getClientId();

        log.info("Client disconnected: {} (abnormal: {})", clientId, abnormal);

        // Handle Will message if abnormal disconnect
        if (abnormal && session.getWillMessage() != null) {
            publishWillMessage(session);
        }

        // Remove local subscriptions
        subscriptionManager.removeAllSubscriptions(clientId);

        // Handle session cleanup
        if (session.isCleanSession()) {
            // Clean session: remove completely
            return sessionManager.removeSession(clientId, true);
        } else {
            // Persistent session: just mark as disconnected
            session.setEndpoint(null);
            return sessionManager.updateSession(session);
        }
    }

    private void publishWillMessage(ClientSession session) {
        ClientSession.WillMessage will = session.getWillMessage();

        log.info("Publishing Will message for {}: topic={}", session.getClientId(), will.getTopic());

        // Publish will message via Event Bus to be handled by PublishHandler
        vertx.eventBus().publish("mqtt.will.publish",
                io.vertx.core.json.JsonObject.of(
                        "clientId", session.getClientId(),
                        "topic", will.getTopic(),
                        "payload", will.getPayload(),
                        "qos", will.getQos(),
                        "retain", will.isRetain()));
    }
}
