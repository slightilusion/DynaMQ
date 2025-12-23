package org.dynabot.mqtt.handler;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttTopicSubscription;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttUnsubscribeMessage;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.acl.AclProvider;
import org.dynabot.metrics.MetricsVerticle;
import org.dynabot.retain.RetainMessageStore;
import org.dynabot.retain.RetainedMessage;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;
import org.dynabot.subscription.SubscriptionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles MQTT SUBSCRIBE and UNSUBSCRIBE messages.
 */
@Slf4j
public class SubscribeHandler {

    private final Vertx vertx;
    private final SessionManager sessionManager;
    private final SubscriptionManager subscriptionManager;
    private final RetainMessageStore retainMessageStore;
    private final AclProvider aclProvider;

    public SubscribeHandler(Vertx vertx, SessionManager sessionManager,
            SubscriptionManager subscriptionManager, RetainMessageStore retainMessageStore,
            AclProvider aclProvider) {
        this.vertx = vertx;
        this.sessionManager = sessionManager;
        this.subscriptionManager = subscriptionManager;
        this.retainMessageStore = retainMessageStore;
        this.aclProvider = aclProvider;
    }

    /**
     * Handle SUBSCRIBE message
     */
    public Future<Void> handle(MqttEndpoint endpoint, ClientSession session, MqttSubscribeMessage message) {
        List<MqttTopicSubscription> subscriptions = message.topicSubscriptions();
        List<MqttQoS> grantedQosLevels = new ArrayList<>();
        List<String> successfulTopicFilters = new ArrayList<>();

        // Process subscriptions sequentially to handle ACL checks
        return processSubscriptions(endpoint, session, subscriptions, 0, grantedQosLevels, successfulTopicFilters)
                .compose(v -> {
                    // Update metrics
                    if (!successfulTopicFilters.isEmpty()) {
                        MetricsVerticle.incrementSubscriptions(successfulTopicFilters.size());
                    }

                    // Send SUBACK
                    endpoint.subscribeAcknowledge(message.messageId(), grantedQosLevels);

                    // Send retained messages for successful subscriptions
                    for (String topicFilter : successfulTopicFilters) {
                        sendRetainedMessages(endpoint, session, topicFilter);
                    }

                    // Update session
                    return sessionManager.updateSession(session);
                });
    }

    private Future<Void> processSubscriptions(MqttEndpoint endpoint, ClientSession session,
            List<MqttTopicSubscription> subscriptions, int index,
            List<MqttQoS> grantedQosLevels, List<String> successfulTopicFilters) {

        if (index >= subscriptions.size()) {
            return Future.succeededFuture();
        }

        MqttTopicSubscription subscription = subscriptions.get(index);
        String topicFilter = subscription.topicName();
        MqttQoS requestedQos = subscription.qualityOfService();

        // Validate topic filter
        if (!isValidTopicFilter(topicFilter)) {
            log.warn("Invalid topic filter from {}: {}", session.getClientId(), topicFilter);
            grantedQosLevels.add(MqttQoS.FAILURE);
            return processSubscriptions(endpoint, session, subscriptions, index + 1, grantedQosLevels,
                    successfulTopicFilters);
        }

        // Check ACL permission for subscribe
        return aclProvider.checkPermission(session.getClientId(), session.getUsername(),
                AclProvider.ACTION_SUBSCRIBE, topicFilter)
                .compose(allowed -> {
                    if (!allowed) {
                        log.warn("ACL denied SUBSCRIBE: client={}, topic={}", session.getClientId(), topicFilter);
                        grantedQosLevels.add(MqttQoS.FAILURE);
                    } else {
                        // Grant the requested QoS
                        MqttQoS grantedQos = requestedQos;
                        grantedQosLevels.add(grantedQos);

                        // Add subscription
                        subscriptionManager.addSubscription(session.getClientId(), topicFilter, grantedQos.value());
                        session.addSubscription(topicFilter, grantedQos.value());
                        successfulTopicFilters.add(topicFilter);

                        log.debug("Subscription added: client={}, topic={}, qos={}",
                                session.getClientId(), topicFilter, grantedQos);
                    }
                    return processSubscriptions(endpoint, session, subscriptions, index + 1, grantedQosLevels,
                            successfulTopicFilters);
                });
    }

    /**
     * Send retained messages matching the topic filter to the subscriber
     */
    private void sendRetainedMessages(MqttEndpoint endpoint, ClientSession session, String topicFilter) {
        if (retainMessageStore == null) {
            return;
        }

        retainMessageStore.getMatching(topicFilter)
                .onSuccess(retainedMessages -> {
                    for (RetainedMessage retained : retainedMessages) {
                        // Calculate effective QoS
                        Integer subscriberQos = session.getSubscriptions().get(topicFilter);
                        int effectiveQos = subscriberQos != null
                                ? Math.min(retained.getQos(), subscriberQos)
                                : retained.getQos();

                        int messageId = effectiveQos > 0 ? session.nextMessageId() : 0;

                        endpoint.publish(
                                retained.getTopic(),
                                retained.getPayloadAsBuffer(),
                                MqttQoS.valueOf(effectiveQos),
                                false, // dup
                                true, // retain flag for delivered retained messages
                                messageId);

                        log.debug("Sent retained message to {}: topic={}, qos={}",
                                session.getClientId(), retained.getTopic(), effectiveQos);
                    }
                })
                .onFailure(err -> log.error("Failed to get retained messages for filter {}: {}",
                        topicFilter, err.getMessage()));
    }

    /**
     * Handle UNSUBSCRIBE message
     */
    public Future<Void> handleUnsubscribe(MqttEndpoint endpoint, ClientSession session,
            MqttUnsubscribeMessage message) {
        for (String topicFilter : message.topics()) {
            subscriptionManager.removeSubscription(session.getClientId(), topicFilter);
            session.removeSubscription(topicFilter);

            log.debug("Subscription removed: client={}, topic={}", session.getClientId(), topicFilter);
        }

        // Send UNSUBACK
        endpoint.unsubscribeAcknowledge(message.messageId());

        // Update session
        return sessionManager.updateSession(session);
    }

    /**
     * Validate topic filter
     */
    private boolean isValidTopicFilter(String topicFilter) {
        if (topicFilter == null || topicFilter.isEmpty()) {
            return false;
        }

        // Check for invalid wildcard usage
        String[] levels = topicFilter.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];

            // # must be last level
            if (level.contains("#")) {
                if (!level.equals("#") || i != levels.length - 1) {
                    return false;
                }
            }

            // + must occupy entire level
            if (level.contains("+") && !level.equals("+")) {
                return false;
            }
        }

        return true;
    }
}
