package org.dynabot.kafka;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Kafka consumer for receiving commands from Kafka and delivering to MQTT
 * clients.
 * Listens to command topics and routes messages to connected devices.
 */
@Slf4j
public class KafkaCommandConsumer {

    private static final String COMMAND_TOPIC = "dynamq.commands";

    private final Vertx vertx;
    private final AppConfig config;
    private final SessionManager sessionManager;
    private final KafkaConsumer<String, String> consumer;

    public KafkaCommandConsumer(Vertx vertx, AppConfig config, SessionManager sessionManager) {
        this.vertx = vertx;
        this.config = config;
        this.sessionManager = sessionManager;

        // Create Kafka consumer configuration
        Map<String, String> consumerConfig = new HashMap<>();
        consumerConfig.put("bootstrap.servers", config.getKafkaBootstrapServers());
        consumerConfig.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerConfig.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerConfig.put("group.id", config.getKafkaConsumerGroupId());
        consumerConfig.put("auto.offset.reset", config.getKafkaConsumerAutoOffsetReset());
        consumerConfig.put("enable.auto.commit", String.valueOf(config.isKafkaConsumerEnableAutoCommit()));

        this.consumer = KafkaConsumer.create(vertx, consumerConfig);

        log.info("Kafka command consumer initialized: bootstrap={}, groupId={}",
                config.getKafkaBootstrapServers(), config.getKafkaConsumerGroupId());
    }

    /**
     * Start consuming commands from Kafka
     */
    public Future<Void> start() {
        // Set up message handler
        consumer.handler(this::handleMessage);

        // Set up error handler
        consumer.exceptionHandler(err -> {
            log.error("Kafka consumer error: {}", err.getMessage());
        });

        // Subscribe to command topics
        return consumer.subscribe(Set.of(COMMAND_TOPIC, config.getKafkaTopicPrefix() + "commands"))
                .onSuccess(v -> log.info("Subscribed to Kafka topics: {}, {}commands",
                        COMMAND_TOPIC, config.getKafkaTopicPrefix()))
                .onFailure(err -> log.error("Failed to subscribe to Kafka topics: {}", err.getMessage()));
    }

    /**
     * Stop the consumer
     */
    public Future<Void> stop() {
        return consumer.close()
                .onSuccess(v -> log.info("Kafka command consumer stopped"));
    }

    /**
     * Handle incoming Kafka message
     */
    private void handleMessage(KafkaConsumerRecord<String, String> record) {
        log.debug("Received Kafka message: topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        try {
            JsonObject message = new JsonObject(record.value());
            String clientId = message.getString("clientId");
            String mqttTopic = message.getString("topic");
            String payloadBase64 = message.getString("payload");
            Integer qos = message.getInteger("qos", 1);

            if (clientId == null || mqttTopic == null) {
                log.warn("Invalid command message, missing clientId or topic: {}", record.value());
                return;
            }

            // Decode payload
            byte[] payloadBytes;
            if (payloadBase64 != null) {
                try {
                    payloadBytes = Base64.getDecoder().decode(payloadBase64);
                } catch (IllegalArgumentException e) {
                    // Not base64 encoded, treat as plain text
                    payloadBytes = payloadBase64.getBytes();
                }
            } else {
                payloadBytes = new byte[0];
            }

            Buffer payload = Buffer.buffer(payloadBytes);

            // Deliver to client
            deliverToClient(clientId, mqttTopic, payload, qos);

        } catch (Exception e) {
            log.error("Failed to process Kafka message: {}", e.getMessage(), e);
        }
    }

    /**
     * Deliver a command message to a specific MQTT client
     */
    private void deliverToClient(String clientId, String topic, Buffer payload, int qos) {
        sessionManager.getSession(clientId)
                .onSuccess(optSession -> {
                    if (optSession.isPresent()) {
                        ClientSession session = optSession.get();

                        if (session.isConnected() && session.getEndpoint() != null) {
                            int messageId = qos > 0 ? session.nextMessageId() : 0;

                            // Track pending message for QoS > 0
                            if (qos >= 1) {
                                ClientSession.PendingMessage pending = ClientSession.PendingMessage.builder()
                                        .messageId(messageId)
                                        .topic(topic)
                                        .payload(payload.getBytes())
                                        .qos(qos)
                                        .sentAt(java.time.Instant.now())
                                        .retryCount(0)
                                        .build();

                                if (qos == 1) {
                                    session.getPendingQoS1().put(messageId, pending);
                                } else if (qos == 2) {
                                    session.getPendingQoS2().put(messageId, pending);
                                }
                            }

                            session.getEndpoint().publish(
                                    topic,
                                    payload,
                                    MqttQoS.valueOf(qos),
                                    false, // dup
                                    false, // retain
                                    messageId);

                            log.info("Delivered command to {}: topic={}, qos={}, size={}",
                                    clientId, topic, qos, payload.length());
                        } else {
                            log.warn("Client {} is not connected locally, routing via Event Bus", clientId);
                            routeViaEventBus(clientId, topic, payload, qos);
                        }
                    } else {
                        log.warn("Session not found for client: {}", clientId);
                    }
                })
                .onFailure(err -> {
                    log.error("Failed to get session for client {}: {}", clientId, err.getMessage());
                });
    }

    /**
     * Route message via Vert.x Event Bus for clustered delivery
     */
    private void routeViaEventBus(String clientId, String topic, Buffer payload, int qos) {
        sessionManager.getClientNode(clientId)
                .onSuccess(optNode -> {
                    if (optNode.isPresent()) {
                        String nodeId = optNode.get();
                        JsonObject message = new JsonObject()
                                .put("clientId", clientId)
                                .put("topic", topic)
                                .put("payload", payload.getBytes())
                                .put("qos", qos);

                        vertx.eventBus().publish("mqtt.deliver." + nodeId, message);
                        log.debug("Routed command via Event Bus to node: {}", nodeId);
                    } else {
                        log.warn("Client {} is not connected to any node", clientId);
                    }
                });
    }
}
