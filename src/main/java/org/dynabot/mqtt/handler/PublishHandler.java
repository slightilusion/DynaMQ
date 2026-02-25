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
public class PublishHandler {

    private final SessionManager sessionManager;
    private final SubscriptionManager subscriptionManager;
    private final KafkaPublisher kafkaPublisher;
    private final RetainMessageStore retainMessageStore;
    private final ClusterMessageRouter clusterRouter;
    private final AclProvider aclProvider;

    public PublishHandler(Vertx vertx, SessionManager sessionManager,
            SubscriptionManager subscriptionManager, AppConfig config,
            RetainMessageStore retainMessageStore, ClusterMessageRouter clusterRouter,
            AclProvider aclProvider, RouteManager routeManager) {
        this.sessionManager = sessionManager;
        this.subscriptionManager = subscriptionManager;
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
                    Future<Void> routeFuture = routeToLocalSubscribers(session.getClientId(), topic, payload, qos,
                            retain);

                    // Broadcast to cluster
                    Future<Void> broadcastFuture = clusterRouter != null
                            ? clusterRouter.broadcastToCluster(topic, payload.getBytes(), qos.value(), retain,
                                    session.getClientId())
                            : Future.succeededFuture();

                    // Publish to Kafka
                    Future<Void> kafkaFuture = publishToKafka(session.getClientId(), topic, payload, qos);

                    // Handle retain message
                    Future<Void> retainFuture = retain ? handleRetainMessage(topic, payload, qos)
                            : Future.succeededFuture();

                    return Future.all(routeFuture, broadcastFuture, kafkaFuture, retainFuture).mapEmpty();
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

    private Future<Void> routeToLocalSubscribers(String publisherClientId, String topic,
            Buffer payload, MqttQoS qos, boolean retain) {
        Map<String, Integer> subscribers = subscriptionManager.findMatchingSubscribers(topic);

        if (subscribers.isEmpty()) {
            log.trace("No subscribers for topic: {}", topic);
            return Future.succeededFuture();
        }

        log.debug("Routing to {} local subscribers for topic: {}", subscribers.size(), topic);

        // Route to each subscriber
        for (Map.Entry<String, Integer> entry : subscribers.entrySet()) {
            String subscriberClientId = entry.getKey();
            if (subscriberClientId.equals(publisherClientId)) {
                // MQTT standard: publishers can receive their own messages if subscribed
            }
            int subscriberQos = entry.getValue();
            int effectiveQos = Math.min(qos.value(), subscriberQos);

            deliverToLocalSubscriber(subscriberClientId, topic, payload, effectiveQos, retain);
        }

        return Future.succeededFuture();
    }

    private void deliverToLocalSubscriber(String clientId, String topic, Buffer payload, int qos, boolean retain) {
        sessionManager.getSession(clientId)
                .onSuccess(optSession -> {
                    if (optSession.isPresent()) {
                        ClientSession session = optSession.get();
                        MqttEndpoint endpoint = session.getEndpoint();

                        if (endpoint != null && endpoint.isConnected()) {
                            int messageId = qos > 0 ? session.nextMessageId() : 0;
                            endpoint.publish(topic, payload, MqttQoS.valueOf(qos), false, retain, messageId);
                            log.trace("Delivered to local {}: topic={}, qos={}", clientId, topic, qos);
                        }
                    }
                })
                .onFailure(err -> log.warn("Failed to deliver to {}: {}", clientId, err.getMessage()));
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

    /**
     * Close the Kafka publisher for resource cleanup.
     */
    public void closeKafkaPublisher() {
        if (kafkaPublisher != null) {
            kafkaPublisher.close();
            log.info("Kafka publisher closed");
        }
    }
}
