package org.dynabot.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Data route configuration for MQTT to Kafka forwarding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataRoute {

    private String id;
    private String mqttTopicPattern; // MQTT topic pattern (supports + and #)
    private String kafkaTopic; // Target Kafka topic
    private boolean enabled;
    private TransformType transformType;
    private String description;

    // Compiled regex pattern (transient, not serialized)
    private transient Pattern compiledPattern;

    public enum TransformType {
        RAW, // Forward raw payload
        JSON_WRAP // Wrap in JSON envelope with metadata
    }

    /**
     * Generate a new unique ID for this route
     */
    public static String generateId() {
        return "route-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Compile the MQTT topic pattern to a regex pattern
     */
    public Pattern getCompiledPattern() {
        if (compiledPattern == null && mqttTopicPattern != null) {
            // Convert MQTT wildcards to regex:
            // + matches any single level
            // # matches any number of levels (only at end)
            String regex = mqttTopicPattern
                    .replace("+", "[^/]+")
                    .replace("#", ".*");
            compiledPattern = Pattern.compile("^" + regex + "$");
        }
        return compiledPattern;
    }

    /**
     * Check if this route matches the given MQTT topic
     */
    public boolean matches(String mqttTopic) {
        if (!enabled || mqttTopic == null) {
            return false;
        }
        Pattern pattern = getCompiledPattern();
        return pattern != null && pattern.matcher(mqttTopic).matches();
    }

    /**
     * Reset compiled pattern (call after changing mqttTopicPattern)
     */
    public void resetPattern() {
        this.compiledPattern = null;
    }
}
