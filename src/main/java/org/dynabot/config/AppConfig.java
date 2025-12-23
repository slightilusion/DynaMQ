package org.dynabot.config;

import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Application configuration holder.
 * Provides typed access to configuration values from application.yml
 */
@Slf4j
@Getter
public class AppConfig {

    private final JsonObject config;

    // Server configuration
    private final int serverPort;
    private final int sslPort;
    private final int websocketPort;
    private final int maxConnections;
    private final int verticleInstances;

    // MQTT configuration
    private final int maxMessageSize;
    private final int defaultQos;
    private final int keepAliveMax;
    private final int sessionExpiry;

    // Cluster configuration
    private final boolean clusterEnabled;
    private final String clusterType;
    private final String nodeName;

    // Redis configuration
    private final boolean redisEnabled;
    private final String redisConnectionString;
    private final int redisMaxPoolSize;
    private final int redisMaxWaitingHandlers;

    // Kafka configuration
    private final boolean kafkaEnabled;
    private final String kafkaBootstrapServers;
    private final String kafkaTopicPrefix;
    private final String kafkaProducerAcks;
    private final int kafkaProducerRetries;
    private final int kafkaProducerBatchSize;
    private final int kafkaProducerLingerMs;
    private final String kafkaConsumerGroupId;
    private final String kafkaConsumerAutoOffsetReset;
    private final boolean kafkaConsumerEnableAutoCommit;

    // Metrics configuration
    private final boolean metricsEnabled;
    private final int metricsPort;
    private final String metricsPath;

    // SSL configuration
    private final boolean sslEnabled;
    private final String sslCertPath;
    private final String sslKeyPath;
    private final String sslKeyPassword;
    private final String sslClientAuth;

    // WebSocket configuration
    private final boolean websocketEnabled;
    private final String websocketPath;

    // Auth configuration
    private final boolean authEnabled;
    private final String authProvider;

    // ACL configuration
    private final boolean aclEnabled;
    private final String aclProvider;
    private final boolean aclDefaultAllow;

    // Admin API configuration
    private final int adminPort;
    private final boolean adminAuthEnabled;
    private final String adminApiKey;
    private final boolean rateLimitEnabled;
    private final int maxConnectionsPerIp;
    private final int connectRatePerSecond;

    public AppConfig(JsonObject config) {
        this.config = config;

        JsonObject dynamq = config.getJsonObject("dynamq", new JsonObject());

        // Server
        JsonObject server = dynamq.getJsonObject("server", new JsonObject());
        this.serverPort = server.getInteger("port", 1883);
        this.sslPort = server.getInteger("ssl-port", 8883);
        this.websocketPort = server.getInteger("websocket-port", 8083);
        this.maxConnections = server.getInteger("max-connections", 150000);
        this.verticleInstances = server.getInteger("verticle-instances",
                Runtime.getRuntime().availableProcessors());

        // MQTT
        JsonObject mqtt = dynamq.getJsonObject("mqtt", new JsonObject());
        this.maxMessageSize = mqtt.getInteger("max-message-size", 65536);
        this.defaultQos = mqtt.getInteger("default-qos", 1);
        this.keepAliveMax = mqtt.getInteger("keep-alive-max", 300);
        this.sessionExpiry = mqtt.getInteger("session-expiry", 86400);

        // Cluster
        JsonObject cluster = dynamq.getJsonObject("cluster", new JsonObject());
        this.clusterEnabled = cluster.getBoolean("enabled", false);
        this.clusterType = cluster.getString("type", "hazelcast");
        this.nodeName = resolveEnvVar(cluster.getString("node-name", "node-1"));

        // Redis
        JsonObject redis = dynamq.getJsonObject("redis", new JsonObject());
        this.redisEnabled = redis.getBoolean("enabled", true);
        this.redisConnectionString = redis.getString("connection-string", "redis://localhost:6379");
        this.redisMaxPoolSize = redis.getInteger("max-pool-size", 32);
        this.redisMaxWaitingHandlers = redis.getInteger("max-waiting-handlers", 64);

        // Kafka
        JsonObject kafka = dynamq.getJsonObject("kafka", new JsonObject());
        this.kafkaEnabled = kafka.getBoolean("enabled", true);
        this.kafkaBootstrapServers = kafka.getString("bootstrap-servers", "localhost:9092");
        this.kafkaTopicPrefix = kafka.getString("topic-prefix", "dynamq.");

        JsonObject producer = kafka.getJsonObject("producer", new JsonObject());
        this.kafkaProducerAcks = producer.getString("acks", "1");
        this.kafkaProducerRetries = producer.getInteger("retries", 3);
        this.kafkaProducerBatchSize = producer.getInteger("batch-size", 16384);
        this.kafkaProducerLingerMs = producer.getInteger("linger-ms", 5);

        JsonObject consumer = kafka.getJsonObject("consumer", new JsonObject());
        this.kafkaConsumerGroupId = consumer.getString("group-id", "dynamq-broker");
        this.kafkaConsumerAutoOffsetReset = consumer.getString("auto-offset-reset", "latest");
        this.kafkaConsumerEnableAutoCommit = consumer.getBoolean("enable-auto-commit", true);

        // Metrics
        JsonObject metrics = dynamq.getJsonObject("metrics", new JsonObject());
        this.metricsEnabled = metrics.getBoolean("enabled", true);
        this.metricsPort = metrics.getInteger("port", 9090);
        this.metricsPath = metrics.getString("path", "/metrics");

        // SSL
        JsonObject ssl = dynamq.getJsonObject("ssl", new JsonObject());
        this.sslEnabled = ssl.getBoolean("enabled", false);
        this.sslCertPath = resolveEnvVar(ssl.getString("cert-path", "certs/server.crt"));
        this.sslKeyPath = resolveEnvVar(ssl.getString("key-path", "certs/server.key"));
        this.sslKeyPassword = resolveEnvVar(ssl.getString("key-password", ""));
        this.sslClientAuth = ssl.getString("client-auth", "none");

        // WebSocket
        JsonObject websocket = dynamq.getJsonObject("websocket", new JsonObject());
        this.websocketEnabled = websocket.getBoolean("enabled", false);
        this.websocketPath = websocket.getString("path", "/mqtt");

        // Auth
        JsonObject auth = dynamq.getJsonObject("auth", new JsonObject());
        this.authEnabled = auth.getBoolean("enabled", false);
        this.authProvider = auth.getString("provider", "simple");

        // ACL
        JsonObject acl = dynamq.getJsonObject("acl", new JsonObject());
        this.aclEnabled = acl.getBoolean("enabled", false);
        this.aclProvider = acl.getString("provider", "simple");
        this.aclDefaultAllow = acl.getBoolean("default-allow", true);

        // Admin API
        JsonObject admin = dynamq.getJsonObject("admin", new JsonObject());
        this.adminPort = admin.getInteger("port", 8080);
        JsonObject adminAuth = admin.getJsonObject("auth", new JsonObject());
        this.adminAuthEnabled = adminAuth.getBoolean("enabled", false);
        this.adminApiKey = resolveEnvVar(adminAuth.getString("api-key", "dynamq-admin-secret-key"));
        JsonObject rateLimit = admin.getJsonObject("rate-limit", new JsonObject());
        this.rateLimitEnabled = rateLimit.getBoolean("enabled", false);
        this.maxConnectionsPerIp = rateLimit.getInteger("max-connections-per-ip", 100);
        this.connectRatePerSecond = rateLimit.getInteger("connect-rate-per-second", 50);

        log.info(
                "Configuration loaded: port={}, ssl={}, websocket={}, auth={}, acl={}, cluster={}, redis={}, kafka={}, adminAuth={}",
                serverPort, sslEnabled, websocketEnabled, authEnabled, aclEnabled, clusterEnabled, redisEnabled,
                kafkaEnabled, adminAuthEnabled);
    }

    /**
     * Resolve environment variable placeholders like ${VAR:default}
     */
    private String resolveEnvVar(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }

        int start = value.indexOf("${");
        int end = value.indexOf("}", start);
        if (end == -1) {
            return value;
        }

        String placeholder = value.substring(start + 2, end);
        String[] parts = placeholder.split(":", 2);
        String envVar = parts[0];
        String defaultValue = parts.length > 1 ? parts[1] : "";

        String envValue = System.getenv(envVar);
        String resolved = envValue != null ? envValue : defaultValue;

        return value.substring(0, start) + resolved + value.substring(end + 1);
    }
}
