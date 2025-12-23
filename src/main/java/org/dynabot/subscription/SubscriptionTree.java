package org.dynabot.subscription;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trie-based subscription tree for efficient topic matching.
 * Supports MQTT wildcards: + (single level) and # (multi level).
 */
@Slf4j
public class SubscriptionTree {

    private final Node root = new Node();

    /**
     * Add a subscription to the tree
     */
    public void addSubscription(String clientId, String topicFilter, int qos) {
        String[] levels = topicFilter.split("/", -1);
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
        String[] levels = topicFilter.split("/", -1);
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
        String[] levels = topic.split("/", -1);
        matchRecursive(root, levels, 0, result);
        return result;
    }

    private void matchRecursive(Node node, String[] levels, int index, Map<String, Integer> result) {
        if (node == null) {
            return;
        }

        // Check for # wildcard at current level
        Node hashNode = node.children.get("#");
        if (hashNode != null) {
            // # matches everything from here
            addSubscribers(hashNode, result);
        }

        if (index >= levels.length) {
            // Reached end of topic, add subscribers at this node
            addSubscribers(node, result);
            return;
        }

        String level = levels[index];

        // Check for + wildcard
        Node plusNode = node.children.get("+");
        if (plusNode != null) {
            matchRecursive(plusNode, levels, index + 1, result);
        }

        // Check for exact match
        Node exactNode = node.children.get(level);
        if (exactNode != null) {
            matchRecursive(exactNode, levels, index + 1, result);
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
        String[] filterLevels = topicFilter.split("/", -1);
        String[] topicLevels = topic.split("/", -1);

        int filterIndex = 0;
        int topicIndex = 0;

        while (filterIndex < filterLevels.length) {
            String filterLevel = filterLevels[filterIndex];

            if ("#".equals(filterLevel)) {
                // # matches everything remaining
                return true;
            }

            if (topicIndex >= topicLevels.length) {
                // Topic exhausted but filter has more levels
                return false;
            }

            String topicLevel = topicLevels[topicIndex];

            if ("+".equals(filterLevel)) {
                // + matches any single level
                filterIndex++;
                topicIndex++;
                continue;
            }

            if (!filterLevel.equals(topicLevel)) {
                // Exact match failed
                return false;
            }

            filterIndex++;
            topicIndex++;
        }

        // Both must be exhausted
        return topicIndex >= topicLevels.length;
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
