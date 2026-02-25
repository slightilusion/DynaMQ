package org.dynabot.subscription;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Trie-based subscription tree for efficient topic matching.
 * Supports MQTT wildcards: + (single level) and # (multi level).
 */
@Slf4j
public class SubscriptionTree {

    // Pre-compiled pattern for better performance (avoid creating new Pattern each
    // time)
    private static final Pattern TOPIC_SEPARATOR = Pattern.compile("/", Pattern.LITERAL);

    private final Node root = new Node();

    /**
     * Add a subscription to the tree
     */
    public void addSubscription(String clientId, String topicFilter, int qos) {
        String[] levels = TOPIC_SEPARATOR.split(topicFilter, -1);
        Node current = root;

        for (String level : levels) {
            current = current.children.computeIfAbsent(level, k -> new Node());
        }

        current.subscribers.put(clientId, qos);
    }

    /**
     * Remove a subscription from the tree
     */
    public void removeSubscription(String clientId, String topicFilter) {
        String[] levels = TOPIC_SEPARATOR.split(topicFilter, -1);
        Node current = root;

        for (String level : levels) {
            current = current.children.get(level);
            if (current == null) {
                return; // Subscription doesn't exist
            }
        }

        current.subscribers.remove(clientId);
    }

    /**
     * Find all subscribers matching a topic
     * 
     * @param topic Published topic (no wildcards)
     * @return Map of clientId -> QoS
     */
    public Map<String, Integer> match(String topic) {
        Map<String, Integer> result = new HashMap<>();
        matchRecursiveIter(root, topic, 0, result);
        return result;
    }

    private void matchRecursiveIter(Node node, String topic, int startIndex, Map<String, Integer> result) {
        if (node == null) {
            return;
        }

        // Check for # wildcard at current level
        Node hashNode = node.children.get("#");
        if (hashNode != null) {
            // # matches everything from here
            addSubscribers(hashNode, result);
        }

        int nextSlash = topic.indexOf('/', startIndex);
        String level;
        int nextIndex;

        if (nextSlash == -1) {
            level = topic.substring(startIndex);
            nextIndex = -1; // Indicate end of topic
        } else {
            level = topic.substring(startIndex, nextSlash);
            nextIndex = nextSlash + 1;
        }

        // Check for + wildcard
        Node plusNode = node.children.get("+");
        if (plusNode != null) {
            if (nextIndex == -1) {
                addSubscribers(plusNode, result);
            } else {
                matchRecursiveIter(plusNode, topic, nextIndex, result);
            }
        }

        // Check for exact match
        Node exactNode = node.children.get(level);
        if (exactNode != null) {
            if (nextIndex == -1) {
                addSubscribers(exactNode, result);
            } else {
                matchRecursiveIter(exactNode, topic, nextIndex, result);
            }
        }
    }

    private void addSubscribers(Node node, Map<String, Integer> result) {
        for (Map.Entry<String, Integer> entry : node.subscribers.entrySet()) {
            String clientId = entry.getKey();
            int qos = entry.getValue();
            // Use max QoS if client has multiple matching subscriptions
            result.merge(clientId, qos, Math::max);
        }
    }

    /**
     * Check if a topic matches a filter
     * 
     * @param topicFilter Filter with possible wildcards
     * @param topic       Actual topic
     * @return true if matches
     */
    public static boolean topicMatches(String topicFilter, String topic) {
        int filterLen = topicFilter.length();
        int topicLen = topic.length();
        int filterIndex = 0;
        int topicIndex = 0;

        while (filterIndex < filterLen) {
            int nextFilterSlash = topicFilter.indexOf('/', filterIndex);
            String filterLevel = nextFilterSlash == -1 ? topicFilter.substring(filterIndex)
                    : topicFilter.substring(filterIndex, nextFilterSlash);

            if ("#".equals(filterLevel)) {
                return true;
            }

            if (topicIndex > topicLen) {
                return false;
            }

            int nextTopicSlash = topicIndex <= topicLen && topicIndex < topicLen ? topic.indexOf('/', topicIndex) : -1;
            String topicLevel;

            if (topicIndex == topicLen) {
                topicLevel = ""; // trailing slash edge case
                topicIndex = topicLen + 1;
            } else if (nextTopicSlash == -1) {
                topicLevel = topic.substring(topicIndex);
                topicIndex = topicLen + 1;
            } else {
                topicLevel = topic.substring(topicIndex, nextTopicSlash);
                topicIndex = nextTopicSlash + 1;
            }

            if ("+".equals(filterLevel)) {
                filterIndex = nextFilterSlash == -1 ? filterLen + 1 : nextFilterSlash + 1;
                continue;
            }

            if (!filterLevel.equals(topicLevel)) {
                return false;
            }

            filterIndex = nextFilterSlash == -1 ? filterLen + 1 : nextFilterSlash + 1;
        }

        return topicIndex > topicLen;
    }

    /**
     * Clear all subscriptions from the tree
     */
    public void clear() {
        root.children.clear();
        root.subscribers.clear();
    }

    /**
     * Node in the subscription tree
     */
    private static class Node {
        final ConcurrentHashMap<String, Node> children = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Integer> subscribers = new ConcurrentHashMap<>(); // clientId -> qos
    }
}
