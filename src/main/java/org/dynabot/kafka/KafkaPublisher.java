package org.dynabot.kafka;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka publisher for forwarding MQTT messages to Kafka topics.
 * Messages are published with the MQTT topic as key for partitioning.
 */
@Slf4j
public class KafkaPublisher {

    private final Vertx vertx;
    private final AppConfig config;
    private final KafkaProducer<String, String> producer;
    private final String topicPrefix;

    public KafkaPublisher(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.topicPrefix = config.getKafkaTopicPrefix();

        // Create Kafka producer configuration
        Map<String, String> producerConfig = new HashMap<>();
        producerConfig.put("bootstrap.servers", config.getKafkaBootstrapServers());
        producerConfig.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerConfig.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerConfig.put("acks", config.getKafkaProducerAcks());
        producerConfig.put("retries", String.valueOf(config.getKafkaProducerRetries()));
        producerConfig.put("batch.size", String.valueOf(config.getKafkaProducerBatchSize()));
        producerConfig.put("linger.ms", String.valueOf(config.getKafkaProducerLingerMs()));

        this.producer = KafkaProducer.create(vertx, producerConfig);

        log.info("Kafka publisher initialized: bootstrap={}, topicPrefix={}",
                config.getKafkaBootstrapServers(), topicPrefix);
    }

    /**
     * Publish an MQTT message to Kafka
     * 
     * @param clientId  Source client ID
     * @param mqttTopic MQTT topic
     * @param payload   Message payload
     * @return Future completing when publish is done
     */
    public Future<Void> publish(String clientId, String mqttTopic, Buffer payload) {
        // Determine Kafka topic based on MQTT topic
        String kafkaTopic = getKafkaTopic(mqttTopic);

        // Create message envelope
        JsonObject envelope = new JsonObject()
                .put("clientId", clientId)
                .put("topic", mqttTopic)
                .put("payload", payload.getBytes())
                .put("timestamp", Instant.now().toEpochMilli());

        // Create Kafka record with MQTT topic as key (for partitioning)
        KafkaProducerRecord<String, String> record = KafkaProducerRecord.create(
                kafkaTopic,
                mqttTopic, // key
                envelope.encode() // value
        );

        return producer.send(record)
                .onSuccess(metadata -> {
                    org.dynabot.metrics.MetricsVerticle.incrementKafkaSuccess();
                    log.trace("Published to Kafka: topic={}, partition={}, offset={}",
                            kafkaTopic, metadata.getPartition(), metadata.getOffset());
                })
                .onFailure(err -> {
                    org.dynabot.metrics.MetricsVerticle.incrementKafkaFailed();
                    log.error("Failed to publish to Kafka: topic={}, error={}",
                            kafkaTopic, err.getMessage());
                })
                .mapEmpty();
    }

    /**
     * Map MQTT topic to Kafka topic
     * Default strategy: replace / with . and prepend prefix
     */
    private String getKafkaTopic(String mqttTopic) {
        // Extract top-level category from MQTT topic
        // e.g., "devices/sensor1/telemetry" -> "dynamq.telemetry"
        String[] parts = mqttTopic.split("/");

        if (parts.length >= 3) {
            // Use third level as category (common pattern: devices/{id}/{type})
            return topicPrefix + parts[parts.length - 1];
        } else if (parts.length >= 1) {
            return topicPrefix + parts[0];
        } else {
            return topicPrefix + "messages";
        }
    }

    /**
     * Close the Kafka producer
     */
    public Future<Void> close() {
        return producer.close();
    }
}
