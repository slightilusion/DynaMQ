package org.dynabot;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.admin.AdminApiVerticle;
import org.dynabot.config.AppConfig;
import org.dynabot.metrics.MetricsVerticle;
import org.dynabot.mqtt.MqttBrokerVerticle;

/**
 * DynaMQ Application Entry Point.
 * High-performance MQTT Broker based on Vert.x for million-scale IoT devices.
 */
@Slf4j
public class DynaMQApplication {

    private static Vertx vertx;
    private static AppConfig appConfig;

    public static void main(String[] args) {
        log.info("Starting DynaMQ MQTT Broker...");

        // Load configuration first with a temporary Vertx instance
        Vertx tempVertx = Vertx.vertx();
        loadConfiguration(tempVertx)
                .onSuccess(config -> {
                    tempVertx.close();
                    appConfig = new AppConfig(config);
                    startApplication();
                    registerShutdownHook();
                })
                .onFailure(err -> {
                    log.error("Failed to load configuration", err);
                    tempVertx.close();
                    System.exit(1);
                });
    }

    /**
     * Register JVM shutdown hook for graceful shutdown
     */
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (vertx != null) {
                log.info("Shutdown hook triggered - initiating graceful shutdown...");

                try {
                    // Give time for pending operations to complete
                    long shutdownTimeout = 10000; // 10 seconds

                    vertx.close()
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(shutdownTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);

                    log.info("Graceful shutdown completed successfully");
                } catch (Exception e) {
                    log.error("Error during graceful shutdown: {}", e.getMessage());
                }
            }
        }, "DynaMQ-Shutdown-Hook"));

        log.info("Shutdown hook registered for graceful shutdown");
    }

    private static Future<JsonObject> loadConfiguration(Vertx vertx) {
        ConfigStoreOptions yamlStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(new JsonObject().put("path", "application.yml"));

        ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                .addStore(yamlStore);

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
        return retriever.getConfig();
    }

    private static void startApplication() {
        // Configure Vert.x options with metrics
        VertxOptions vertxOptions = new VertxOptions();

        if (appConfig.isMetricsEnabled()) {
            PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            vertxOptions.setMetricsOptions(new MicrometerMetricsOptions()
                    .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                    .setMicrometerRegistry(registry)
                    .setEnabled(true));
        }

        // Create Vertx instance
        if (appConfig.isClusterEnabled()) {
            startClusteredVertx(vertxOptions);
        } else {
            vertx = Vertx.vertx(vertxOptions);
            deployVerticles();
        }
    }

    private static void startClusteredVertx(VertxOptions options) {
        log.info("Starting in clustered mode...");
        Vertx.clusteredVertx(options)
                .onSuccess(v -> {
                    vertx = v;
                    log.info("Clustered Vertx started successfully");
                    deployVerticles();
                })
                .onFailure(err -> {
                    log.error("Failed to start clustered Vertx", err);
                    System.exit(1);
                });
    }

    private static void deployVerticles() {
        // Deploy Metrics Verticle first if enabled
        Future<String> metricsFuture = appConfig.isMetricsEnabled()
                ? deployMetricsVerticle()
                : Future.succeededFuture();

        // Chain deployments: Metrics -> MQTT Broker -> Admin API
        metricsFuture
                .compose(v -> deployMqttBrokerVerticles())
                .compose(v -> deployAdminApiVerticle())
                .onSuccess(v -> logStartupComplete())
                .onFailure(DynaMQApplication::handleDeploymentFailure);
    }

    private static Future<String> deployMetricsVerticle() {
        log.info("Deploying MetricsVerticle...");
        DeploymentOptions options = new DeploymentOptions()
                .setConfig(appConfig.getConfig());
        return vertx.deployVerticle(new MetricsVerticle(), options);
    }

    private static Future<String> deployMqttBrokerVerticles() {
        log.info("Deploying {} MqttBrokerVerticle instances...", appConfig.getVerticleInstances());

        DeploymentOptions options = new DeploymentOptions()
                .setInstances(appConfig.getVerticleInstances())
                .setConfig(appConfig.getConfig());

        return vertx.deployVerticle(MqttBrokerVerticle.class.getName(), options);
    }

    private static Future<String> deployAdminApiVerticle() {
        log.info("Deploying AdminApiVerticle...");
        DeploymentOptions options = new DeploymentOptions()
                .setConfig(appConfig.getConfig());
        return vertx.deployVerticle(new AdminApiVerticle(), options);
    }

    private static void logStartupComplete() {
        int adminPort = appConfig.getConfig().getInteger("admin.port", 8080);
        log.info("==================================================");
        log.info("  DynaMQ MQTT Broker Started Successfully!");
        log.info("==================================================");
        log.info("  MQTT Port:      {}", appConfig.getServerPort());
        log.info("  Admin API:      http://localhost:{}", adminPort);
        log.info("  Verticles:      {}", appConfig.getVerticleInstances());
        log.info("  Cluster:        {}", appConfig.isClusterEnabled() ? "enabled" : "disabled");
        log.info("  Redis:          {}", appConfig.isRedisEnabled() ? "enabled" : "disabled");
        log.info("  Kafka:          {}", appConfig.isKafkaEnabled() ? "enabled" : "disabled");
        log.info("  Metrics:        http://localhost:{}{}", appConfig.getMetricsPort(), appConfig.getMetricsPath());
        log.info("==================================================");
    }

    private static void handleDeploymentFailure(Throwable err) {
        log.error("Failed to deploy verticles", err);
        if (vertx != null) {
            vertx.close();
        }
        System.exit(1);
    }

    /**
     * Get the shared AppConfig instance
     */
    public static AppConfig getAppConfig() {
        return appConfig;
    }

    /**
     * Get the Vertx instance
     */
    public static Vertx getVertx() {
        return vertx;
    }
}
