package org.dynabot.mqtt.handler;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.messages.MqttPublishMessage;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.acl.AclProvider;
import org.dynabot.cluster.ClusterMessageRouter;
import org.dynabot.config.AppConfig;
import org.dynabot.kafka.KafkaPublisher;
import org.dynabot.retain.RetainMessageStore;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;
import org.dynabot.subscription.SubscriptionManager;

import org.dynabot.routing.RouteManager;

import java.util.Map;

/**
 * Handles MQTT PUBLISH messages.
 * Routes messages to subscribers and publishes to Kafka.
 */
@Slf4j
@SuppressWarnings("unused") // clusterRouter reserved for cluster message forwarding
public class PublishHandler {

    private final Vertx vertx;
    private final SessionManager sessionManager;
    private final SubscriptionManager subscriptionManager;
    private final AppConfig config;
    private final KafkaPublisher kafkaPublisher;
    private final RetainMessageStore retainMessageStore;
    private final ClusterMessageRouter clusterRouter; // TODO: Use for cluster message forwarding
    private final AclProvider aclProvider;

    public PublishHandler(Vertx vertx, SessionManager sessionManager,
            SubscriptionManager subscriptionManager, AppConfig config,
            RetainMessageStore retainMessageStore, ClusterMessageRouter clusterRouter,
            AclProvider aclProvider, RouteManager routeManager) {
        this.vertx = vertx;
        this.sessionManager = sessionManager;
        this.subscriptionManager = subscriptionManager;
        this.config = config;
        this.retainMessageStore = retainMessageStore;
        this.clusterRouter = clusterRouter;
        this.aclProvider = aclProvider;

        // Initialize Kafka publisher if enabled
        if (config.isKafkaEnabled()) {
            this.kafkaPublisher = new KafkaPublisher(vertx, config, routeManager);
        } else {
            this.kafkaPublisher = null;
        }
    }

    /**
     * Handle a PUBLISH message
     */
    public Future<Void> handle(MqttEndpoint endpoint, ClientSession session, MqttPublishMessage message) {
        String topic = message.topicName();
        Buffer payload = message.payload();
        MqttQoS qos = message.qosLevel();
        boolean retain = message.isRetain();
        int messageId = message.messageId();

        log.debug("PUBLISH: client={}, topic={}, qos={}, retain={}, size={}",
                session.getClientId(), topic, qos, retain, payload.length());

        session.touch();

        // Check ACL permission for publish
        return aclProvider.checkPermission(session.getClientId(), session.getUsername(),
                AclProvider.ACTION_PUBLISH, topic)
                .compose(allowed -> {
                    if (!allowed) {
                        log.warn("ACL denied PUBLISH: client={}, topic={}", session.getClientId(), topic);
                        // For QoS > 0, we still need to acknowledge but don't process
                        handleQoSAck(endpoint, qos, messageId);
                        return Future.succeededFuture();
                    }

                    // Handle QoS acknowledgments
                    handleQoSAck(endpoint, qos, messageId);

                    // Route to local subscribers
                    Future<Void> routeFuture = routeToSubscribers(session.getClientId(), topic, payload, qos, retain);

                    // Publish to Kafka
                    Future<Void> kafkaFuture = publishToKafka(session.getClientId(), topic, payload, qos);

                    // Handle retain message
                    Future<Void> retainFuture = retain ? handleRetainMessage(topic, payload, qos)
                            : Future.succeededFuture();

                    return Future.all(routeFuture, kafkaFuture, retainFuture).mapEmpty();
                });
    }

    private void handleQoSAck(MqttEndpoint endpoint, MqttQoS qos, int messageId) {
        switch (qos) {
            case AT_LEAST_ONCE: // QoS 1
                endpoint.publishAcknowledge(messageId);
                break;
            case EXACTLY_ONCE: // QoS 2
                endpoint.publishReceived(messageId);
                break;
            default:
                // QoS 0 - no acknowledgment
                break;
        }
    }

    private Future<Void> routeToSubscribers(String publisherClientId, String topic,
            Buffer payload, MqttQoS qos, boolean retain) {
        Map<String, Integer> subscribers = subscriptionManager.findMatchingSubscribers(topic);

        if (subscribers.isEmpty()) {
            log.trace("No subscribers for topic: {}", topic);
            return Future.succeededFuture();
        }

        log.debug("Routing to {} subscribers for topic: {}", subscribers.size(), topic);

        // Route to each subscriber
        for (Map.Entry<String, Integer> entry : subscribers.entrySet()) {
            String subscriberClientId = entry.getKey();
            int subscriberQos = entry.getValue();

            // Don't send to publisher themselves (unless subscribed)
            // Actually in MQTT, publishers should receive their own messages if subscribed

            // Get effective QoS (min of publisher and subscriber QoS)
            int effectiveQos = Math.min(qos.value(), subscriberQos);

            // Deliver to subscriber
            deliverToSubscriber(subscriberClientId, topic, payload, effectiveQos);
        }

        return Future.succeededFuture();
    }

    private void deliverToSubscriber(String clientId, String topic, Buffer payload, int qos) {
        sessionManager.getSession(clientId)
                .onSuccess(optSession -> {
                    if (optSession.isPresent()) {
                        ClientSession session = optSession.get();
                        MqttEndpoint endpoint = session.getEndpoint();

                        if (endpoint != null && endpoint.isConnected()) {
                            int messageId = qos > 0 ? session.nextMessageId() : 0;

                            endpoint.publish(topic, payload, MqttQoS.valueOf(qos), false, false, messageId);

                            log.trace("Delivered to {}: topic={}, qos={}", clientId, topic, qos);
                        } else {
                            // Client not connected locally, may need to route via Event Bus
                            routeViaEventBus(clientId, topic, payload, qos);
                        }
                    }
                })
                .onFailure(err -> {
                    log.warn("Failed to deliver to {}: {}", clientId, err.getMessage());
                });
    }

    private void routeViaEventBus(String clientId, String topic, Buffer payload, int qos) {
        // For cluster mode: route message to the node where client is connected
        sessionManager.getClientNode(clientId)
                .onSuccess(optNode -> {
                    if (optNode.isPresent()) {
                        String nodeId = optNode.get();
                        // Publish to the specific node's address
                        vertx.eventBus().publish("mqtt.deliver." + nodeId,
                                io.vertx.core.json.JsonObject.of(
                                        "clientId", clientId,
                                        "topic", topic,
                                        "payload", payload.getBytes(),
                                        "qos", qos));
                    }
                });
    }

    private Future<Void> publishToKafka(String clientId, String topic, Buffer payload, MqttQoS qos) {
        if (kafkaPublisher == null) {
            return Future.succeededFuture();
        }

        return kafkaPublisher.publish(clientId, topic, payload);
    }

    private Future<Void> handleRetainMessage(String topic, Buffer payload, MqttQoS qos) {
        if (retainMessageStore == null) {
            log.debug("Retain message store not available, skipping retain for topic: {}", topic);
            return Future.succeededFuture();
        }

        // Empty payload means clear the retained message
        if (payload == null || payload.length() == 0) {
            log.debug("Clearing retain message for topic: {}", topic);
            return retainMessageStore.remove(topic);
        }

        log.debug("Storing retain message for topic: {}, qos: {}, size: {}",
                topic, qos.value(), payload.length());
        return retainMessageStore.store(topic, payload, qos.value());
    }
}
