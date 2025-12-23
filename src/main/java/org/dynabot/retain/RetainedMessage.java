package org.dynabot.retain;

import io.vertx.core.buffer.Buffer;
import lombok.Builder;
import lombok.Data;

/**
 * Represents a retained MQTT message.
 */
@Data
@Builder
public class RetainedMessage {

    /**
     * The topic this message is retained for
     */
    private String topic;

    /**
     * The message payload
     */
    private byte[] payload;

    /**
     * The QoS level of the message
     */
    private int qos;

    /**
     * Timestamp when the message was stored
     */
    private long timestamp;

    /**
     * Get payload as Vert.x Buffer
     */
    public Buffer getPayloadAsBuffer() {
        return payload != null ? Buffer.buffer(payload) : Buffer.buffer();
    }

    /**
     * Check if this is an empty message (used to clear retain)
     */
    public boolean isEmpty() {
        return payload == null || payload.length == 0;
    }
}
