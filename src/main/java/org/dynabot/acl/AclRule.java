package org.dynabot.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ACL (Access Control List) Rule.
 * Defines permission rules for MQTT operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AclRule {

    /**
     * Rule ID for identification
     */
    private String id;

    /**
     * Client ID pattern (supports wildcards: * for any, prefix* for starts-with)
     */
    private String clientIdPattern;

    /**
     * Username pattern (supports wildcards)
     */
    private String usernamePattern;

    /**
     * Action: connect, publish, subscribe
     */
    private String action;

    /**
     * Topic pattern with MQTT wildcards (+, #)
     */
    private String topicPattern;

    /**
     * Whether this rule allows or denies the action
     */
    private boolean allow;

    /**
     * Priority (higher = evaluated first). Default is 0.
     */
    @Builder.Default
    private int priority = 0;

    /**
     * Check if this rule matches the given criteria
     */
    public boolean matches(String clientId, String username, String actionToCheck, String topic) {
        // Check action
        if (!action.equals("*") && !action.equalsIgnoreCase(actionToCheck)) {
            return false;
        }

        // Check client ID pattern
        if (!matchesPattern(clientIdPattern, clientId)) {
            return false;
        }

        // Check username pattern
        if (!matchesPattern(usernamePattern, username)) {
            return false;
        }

        // Check topic pattern (only for publish/subscribe actions)
        if (topic != null && topicPattern != null && !topicPattern.isEmpty()) {
            if (!matchesTopic(topicPattern, topic)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Simple pattern matching with wildcard support
     */
    private boolean matchesPattern(String pattern, String value) {
        if (pattern == null || pattern.isEmpty() || pattern.equals("*")) {
            return true;
        }
        if (value == null) {
            return false;
        }

        // Handle prefix wildcard (e.g., "device-*")
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return value.startsWith(prefix);
        }

        // Handle suffix wildcard (e.g., "*-sensor")
        if (pattern.startsWith("*")) {
            String suffix = pattern.substring(1);
            return value.endsWith(suffix);
        }

        return pattern.equals(value);
    }

    /**
     * MQTT topic pattern matching with + and # wildcards
     */
    private boolean matchesTopic(String pattern, String topic) {
        if (pattern.equals("#")) {
            return true;
        }

        String[] patternParts = pattern.split("/");
        String[] topicParts = topic.split("/");

        int patternIdx = 0;
        int topicIdx = 0;

        while (patternIdx < patternParts.length && topicIdx < topicParts.length) {
            String patternPart = patternParts[patternIdx];

            if (patternPart.equals("#")) {
                // # matches everything from here
                return true;
            } else if (patternPart.equals("+")) {
                // + matches single level
                patternIdx++;
                topicIdx++;
            } else if (patternPart.equals(topicParts[topicIdx])) {
                patternIdx++;
                topicIdx++;
            } else {
                return false;
            }
        }

        return patternIdx == patternParts.length && topicIdx == topicParts.length;
    }
}
