package org.dynabot.session;

import io.vertx.mqtt.MqttEndpoint;
import lombok.Data;
import lombok.Builder;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an MQTT client session.
 * Stores client state, subscriptions, and pending messages.
 */
@Data
@Builder
public class ClientSession {

    private String clientId;
    private String username;
    private boolean cleanSession;
    private int keepAliveSeconds;
    private Instant connectedAt;
    private Instant lastActivityAt;

    // The MQTT endpoint for this session
    private transient MqttEndpoint endpoint;

    // The node/verticle this session is connected to
    private String nodeId;
    private String verticleId;

    // Subscriptions: topic filter -> QoS level
    @Builder.Default
    private ConcurrentHashMap<String, Integer> subscriptions = new ConcurrentHashMap<>();

    // Will message (if configured)
    private WillMessage willMessage;

    // Session state for QoS > 0
    @Builder.Default
    private ConcurrentHashMap<Integer, PendingMessage> pendingQoS1 = new ConcurrentHashMap<>();
    @Builder.Default
    private ConcurrentHashMap<Integer, PendingMessage> pendingQoS2 = new ConcurrentHashMap<>();

    // Message ID counter
    private int lastMessageId;

    /**
     * Get next message ID (1-65535)
     */
    public synchronized int nextMessageId() {
        lastMessageId = (lastMessageId % 65535) + 1;
        return lastMessageId;
    }

    /**
     * Update last activity timestamp
     */
    public void touch() {
        this.lastActivityAt = Instant.now();
    }

    /**
     * Add a subscription
     */
    public void addSubscription(String topicFilter, int qos) {
        subscriptions.put(topicFilter, qos);
    }

    /**
     * Remove a subscription
     */
    public void removeSubscription(String topicFilter) {
        subscriptions.remove(topicFilter);
    }

    /**
     * Get all subscription topic filters
     */
    public Set<String> getSubscriptionTopics() {
        return subscriptions.keySet();
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return endpoint != null && endpoint.isConnected();
    }

    /**
     * Will message structure
     */
    @Data
    @Builder
    public static class WillMessage {
        private String topic;
        private byte[] payload;
        private int qos;
        private boolean retain;
    }

    /**
     * Pending message for QoS 1/2
     */
    @Data
    @Builder
    public static class PendingMessage {
        private int messageId;
        private String topic;
        private byte[] payload;
        private int qos;
        private Instant sentAt;
        private int retryCount;
    }
}
