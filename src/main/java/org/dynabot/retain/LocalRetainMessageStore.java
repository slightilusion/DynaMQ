package org.dynabot.retain;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.subscription.SubscriptionTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local in-memory implementation of RetainMessageStore.
 * Suitable for single-node deployments or development.
 */
@Slf4j
public class LocalRetainMessageStore implements RetainMessageStore {

    private final ConcurrentHashMap<String, RetainedMessage> store = new ConcurrentHashMap<>();

    @Override
    public Future<Void> store(String topic, Buffer payload, int qos) {
        if (payload == null || payload.length() == 0) {
            // Empty payload means remove the retained message
            return remove(topic);
        }

        RetainedMessage message = RetainedMessage.builder()
                .topic(topic)
                .payload(payload.getBytes())
                .qos(qos)
                .timestamp(System.currentTimeMillis())
                .build();

        store.put(topic, message);
        log.debug("Stored retain message for topic: {}, size: {}", topic, payload.length());
        return Future.succeededFuture();
    }

    @Override
    public Future<Optional<RetainedMessage>> get(String topic) {
        RetainedMessage message = store.get(topic);
        return Future.succeededFuture(Optional.ofNullable(message));
    }

    @Override
    public Future<Void> remove(String topic) {
        RetainedMessage removed = store.remove(topic);
        if (removed != null) {
            log.debug("Removed retain message for topic: {}", topic);
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<List<RetainedMessage>> getMatching(String topicFilter) {
        List<RetainedMessage> result = new ArrayList<>();

        for (RetainedMessage message : store.values()) {
            if (SubscriptionTree.topicMatches(topicFilter, message.getTopic())) {
                result.add(message);
            }
        }

        log.debug("Found {} retain messages matching filter: {}", result.size(), topicFilter);
        return Future.succeededFuture(result);
    }

    /**
     * Get the number of retained messages (for testing/metrics)
     */
    public int getRetainCount() {
        return store.size();
    }
}
