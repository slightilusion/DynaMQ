package org.dynabot.retain;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.dynabot.config.AppConfig;

import java.util.List;
import java.util.Optional;

/**
 * Interface for storing and retrieving MQTT retained messages.
 * Retained messages are stored per topic and delivered to new subscribers.
 */
public interface RetainMessageStore {

    /**
     * Create a retain message store based on configuration
     */
    static RetainMessageStore create(Vertx vertx, AppConfig config) {
        if (config.isRedisEnabled()) {
            return new RedisRetainMessageStore(vertx, config);
        } else {
            return new LocalRetainMessageStore();
        }
    }

    /**
     * Store a retained message for a topic.
     * If payload is empty, the retained message should be removed.
     *
     * @param topic   The topic to store the message for
     * @param payload The message payload (empty to remove)
     * @param qos     The QoS level
     * @return Future completing when storage is done
     */
    Future<Void> store(String topic, Buffer payload, int qos);

    /**
     * Get the retained message for a specific topic.
     *
     * @param topic The exact topic
     * @return Future containing optional retained message
     */
    Future<Optional<RetainedMessage>> get(String topic);

    /**
     * Remove the retained message for a topic.
     *
     * @param topic The topic to remove
     * @return Future completing when removal is done
     */
    Future<Void> remove(String topic);

    /**
     * Get all retained messages matching a topic filter (with wildcards).
     *
     * @param topicFilter Topic filter (may contain + and # wildcards)
     * @return Future containing list of matching retained messages
     */
    Future<List<RetainedMessage>> getMatching(String topicFilter);
}
