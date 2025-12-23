package org.dynabot.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.acl.AclRule;
import org.dynabot.config.AppConfig;
import org.dynabot.metrics.MetricsVerticle;
import org.dynabot.routing.DataRoute;
import org.dynabot.routing.RouteManager;

import java.time.Instant;
import java.util.*;

/**
 * Admin API Controller.
 * Handles all admin REST endpoints.
 */
@Slf4j
public class AdminController {

    private static final String SESSION_KEY_PREFIX = "dynamq:session:";
    private static final String ACTIVE_NODES_KEY = "dynamq:nodes:active";

    /**
     * Parse connectedAt field which may be in various formats:
     * - JsonObject with epochSecond/nano (Jackson serialized Instant)
     * - Number (epoch milliseconds)
     * - String (ISO format or other)
     */
    private String parseConnectedAt(JsonObject session) {
        Object connectedAtObj = session.getValue("connectedAt");
        if (connectedAtObj == null)
            return "";

        try {
            if (connectedAtObj instanceof JsonObject) {
                JsonObject cat = (JsonObject) connectedAtObj;
                long epochSecond = cat.getLong("epochSecond", 0L);
                return java.time.Instant.ofEpochSecond(epochSecond)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } else if (connectedAtObj instanceof Number) {
                // Could be epoch seconds or epoch millis
                long value = ((Number) connectedAtObj).longValue();
                // If value > 1e12, it's likely milliseconds
                if (value > 1_000_000_000_000L) {
                    return java.time.Instant.ofEpochMilli(value)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    return java.time.Instant.ofEpochSecond(value)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
            } else {
                return connectedAtObj.toString();
            }
        } catch (Exception e) {
            return connectedAtObj.toString();
        }
    }

    private static final String NODE_HEARTBEAT_KEY_PREFIX = "dynamq:node:";
    private static final String ACL_RULES_KEY = "dynamq:acl:rules";
    private static final String SUBSCRIPTION_KEY_PREFIX = "dynamq:subscriptions:";

    private final Vertx vertx;
    private final RedisAPI redis;
    private final ObjectMapper objectMapper;
    private final AppConfig config;
    private final RouteManager routeManager;

    public AdminController(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.objectMapper = new ObjectMapper();

        // Create Redis client
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize());

        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        // Initialize RouteManager
        this.routeManager = new RouteManager(vertx, config);
    }

    /**
     * GET /api/admin/dashboard
     * Returns dashboard statistics
     */
    public void getDashboard(RoutingContext ctx) {
        Future<Long> clientsFuture = getClientCount();
        Future<Long> nodesFuture = getNodeCount();

        Future.all(clientsFuture, nodesFuture)
                .onSuccess(cf -> {
                    JsonObject dashboard = new JsonObject()
                            .put("connectedClients", clientsFuture.result())
                            .put("activeNodes", nodesFuture.result())
                            .put("timestamp", Instant.now().toString())
                            .put("uptime", getUptime())
                            .put("version", "1.0.0");

                    sendJson(ctx, dashboard);
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * GET /api/admin/clients
     * Returns list of connected clients
     */
    public void getClients(RoutingContext ctx) {
        // Check if onlineOnly filter is requested
        boolean onlineOnly = "true".equalsIgnoreCase(ctx.request().getParam("onlineOnly"));

        // Get all session keys
        redis.keys(SESSION_KEY_PREFIX + "*")
                .onSuccess(keys -> {
                    if (keys == null || keys.size() == 0) {
                        sendJson(ctx, new JsonObject().put("clients", new JsonArray()));
                        return;
                    }

                    List<Future<JsonObject>> clientFutures = new ArrayList<>();
                    for (int i = 0; i < keys.size(); i++) {
                        String key = keys.get(i).toString();
                        String clientId = key.replace(SESSION_KEY_PREFIX, "");
                        clientFutures.add(getClientInfo(clientId));
                    }

                    Future.all(clientFutures)
                            .onSuccess(cf -> {
                                JsonArray clients = new JsonArray();
                                for (Future<JsonObject> f : clientFutures) {
                                    if (f.result() != null) {
                                        JsonObject client = f.result();
                                        // If onlineOnly filter is active, skip offline clients
                                        if (onlineOnly && !client.getBoolean("online", false)) {
                                            continue;
                                        }
                                        clients.add(client);
                                    }
                                }
                                sendJson(ctx, new JsonObject().put("clients", clients));
                            })
                            .onFailure(err -> sendError(ctx, 500, err.getMessage()));
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * GET /api/admin/clients/:clientId
     * Returns detailed info for a single client including subscriptions
     */
    public void getClientDetail(RoutingContext ctx) {
        String clientId = ctx.pathParam("clientId");

        Future<io.vertx.redis.client.Response> sessionFuture = redis.get(SESSION_KEY_PREFIX + clientId);
        Future<io.vertx.redis.client.Response> subsFuture = redis.get(SUBSCRIPTION_KEY_PREFIX + clientId);

        Future.all(sessionFuture, subsFuture)
                .onSuccess(cf -> {
                    io.vertx.redis.client.Response sessionResponse = sessionFuture.result();
                    io.vertx.redis.client.Response subsResponse = subsFuture.result();

                    if (sessionResponse == null) {
                        sendError(ctx, 404, "Client not found: " + clientId);
                        return;
                    }

                    try {
                        JsonObject session = new JsonObject(sessionResponse.toString());

                        JsonObject result = new JsonObject()
                                .put("clientId", clientId)
                                .put("username", session.getString("username", ""))
                                .put("connectedAt", parseConnectedAt(session))
                                .put("nodeId", session.getString("nodeId", ""))
                                .put("cleanSession", session.getBoolean("cleanSession", true))
                                .put("keepAliveSeconds", session.getInteger("keepAliveSeconds", 60));

                        // Add subscriptions
                        JsonArray subscriptions = new JsonArray();
                        if (subsResponse != null) {
                            try {
                                Map<String, Integer> subs = objectMapper.readValue(
                                        subsResponse.toString(),
                                        new TypeReference<Map<String, Integer>>() {
                                        });
                                for (Map.Entry<String, Integer> entry : subs.entrySet()) {
                                    subscriptions.add(new JsonObject()
                                            .put("topic", entry.getKey())
                                            .put("qos", entry.getValue()));
                                }
                            } catch (Exception e) {
                                // ignore parse error
                            }
                        }
                        result.put("subscriptions", subscriptions);
                        result.put("subscriptionCount", subscriptions.size());

                        sendJson(ctx, result);
                    } catch (Exception e) {
                        sendError(ctx, 500, "Failed to parse client data");
                    }
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * DELETE /api/admin/clients/:clientId
     * Kicks a client
     */
    public void kickClient(RoutingContext ctx) {
        String clientId = ctx.pathParam("clientId");

        // Use Event Bus to broadcast disconnect to all local verticles
        vertx.eventBus().publish("mqtt.disconnect." + clientId, clientId);
        log.info("Kick command sent for client: {}", clientId);

        // Also broadcast via Redis for cluster mode
        redis.smembers(ACTIVE_NODES_KEY)
                .onSuccess(nodes -> {
                    if (nodes != null && nodes.size() > 0) {
                        for (int i = 0; i < nodes.size(); i++) {
                            String targetNode = nodes.get(i).toString();
                            JsonObject kickMessage = new JsonObject()
                                    .put("action", "kick")
                                    .put("clientId", clientId)
                                    .put("targetNode", targetNode)
                                    .put("sourceNode", "admin-api");
                            redis.publish("dynamq:cluster:kick", kickMessage.encode());
                        }
                    }
                });

        // Remove session and subscriptions from Redis
        redis.del(List.of(SESSION_KEY_PREFIX + clientId))
                .onSuccess(r -> {
                    redis.del(List.of(SUBSCRIPTION_KEY_PREFIX + clientId));
                    sendJson(ctx, new JsonObject()
                            .put("success", true)
                            .put("message", "Client kicked: " + clientId));
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * GET /api/admin/subscriptions
     * Returns all subscriptions
     */
    public void getSubscriptions(RoutingContext ctx) {
        redis.keys(SUBSCRIPTION_KEY_PREFIX + "*")
                .onSuccess(keys -> {
                    if (keys == null || keys.size() == 0) {
                        sendJson(ctx, new JsonObject().put("subscriptions", new JsonArray()));
                        return;
                    }

                    List<Future<JsonObject>> subFutures = new ArrayList<>();
                    for (int i = 0; i < keys.size(); i++) {
                        String key = keys.get(i).toString();
                        String clientId = key.replace(SUBSCRIPTION_KEY_PREFIX, "");
                        subFutures.add(getClientSubscriptions(clientId));
                    }

                    Future.all(subFutures)
                            .onSuccess(cf -> {
                                JsonArray subscriptions = new JsonArray();
                                for (Future<JsonObject> f : subFutures) {
                                    if (f.result() != null) {
                                        subscriptions.add(f.result());
                                    }
                                }
                                sendJson(ctx, new JsonObject().put("subscriptions", subscriptions));
                            })
                            .onFailure(err -> sendError(ctx, 500, err.getMessage()));
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * GET /api/admin/cluster/nodes
     * Returns cluster node status
     */
    public void getClusterNodes(RoutingContext ctx) {
        redis.smembers(ACTIVE_NODES_KEY)
                .onSuccess(members -> {
                    if (members == null || members.size() == 0) {
                        sendJson(ctx, new JsonObject().put("nodes", new JsonArray()));
                        return;
                    }

                    List<Future<JsonObject>> nodeFutures = new ArrayList<>();
                    for (int i = 0; i < members.size(); i++) {
                        String nodeId = members.get(i).toString();
                        nodeFutures.add(getNodeInfo(nodeId));
                    }

                    Future.all(nodeFutures)
                            .onSuccess(cf -> {
                                JsonArray nodes = new JsonArray();
                                for (Future<JsonObject> f : nodeFutures) {
                                    if (f.result() != null) {
                                        nodes.add(f.result());
                                    }
                                }
                                sendJson(ctx, new JsonObject().put("nodes", nodes));
                            })
                            .onFailure(err -> sendError(ctx, 500, err.getMessage()));
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * GET /api/admin/acl/rules
     * Returns ACL rules
     */
    public void getAclRules(RoutingContext ctx) {
        redis.get(ACL_RULES_KEY)
                .onSuccess(response -> {
                    if (response == null) {
                        sendJson(ctx, new JsonObject().put("rules", new JsonArray()));
                        return;
                    }

                    try {
                        List<AclRule> rules = objectMapper.readValue(
                                response.toString(),
                                new TypeReference<List<AclRule>>() {
                                });

                        JsonArray rulesArray = new JsonArray();
                        for (AclRule rule : rules) {
                            rulesArray.add(JsonObject.mapFrom(rule));
                        }
                        sendJson(ctx, new JsonObject().put("rules", rulesArray));
                    } catch (Exception e) {
                        sendError(ctx, 500, "Failed to parse ACL rules");
                    }
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * POST /api/admin/acl/rules
     * Adds an ACL rule
     */
    public void addAclRule(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();

        AclRule newRule = new AclRule();
        newRule.setId(UUID.randomUUID().toString());
        newRule.setClientIdPattern(body.getString("clientIdPattern", "*"));
        newRule.setUsernamePattern(body.getString("usernamePattern", "*"));
        newRule.setAction(body.getString("action", "*"));
        newRule.setTopicPattern(body.getString("topicPattern", "#"));
        newRule.setAllow(body.getBoolean("allow", true));
        newRule.setPriority(body.getInteger("priority", 0));

        // Get existing rules, add new one, save
        redis.get(ACL_RULES_KEY)
                .onSuccess(response -> {
                    List<AclRule> rules = new ArrayList<>();
                    if (response != null) {
                        try {
                            rules = objectMapper.readValue(
                                    response.toString(),
                                    new TypeReference<List<AclRule>>() {
                                    });
                        } catch (Exception e) {
                            log.error("Failed to parse existing rules", e);
                        }
                    }

                    rules.add(newRule);
                    rules.sort(Comparator.comparingInt(AclRule::getPriority).reversed());

                    try {
                        String json = objectMapper.writeValueAsString(rules);
                        redis.set(List.of(ACL_RULES_KEY, json))
                                .onSuccess(v -> sendJson(ctx, new JsonObject()
                                        .put("success", true)
                                        .put("rule", JsonObject.mapFrom(newRule))))
                                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
                    } catch (Exception e) {
                        sendError(ctx, 500, "Failed to save rule");
                    }
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * DELETE /api/admin/acl/rules/:ruleId
     * Deletes an ACL rule
     */
    public void deleteAclRule(RoutingContext ctx) {
        String ruleId = ctx.pathParam("ruleId");

        redis.get(ACL_RULES_KEY)
                .onSuccess(response -> {
                    if (response == null) {
                        sendError(ctx, 404, "Rule not found");
                        return;
                    }

                    try {
                        List<AclRule> rules = objectMapper.readValue(
                                response.toString(),
                                new TypeReference<List<AclRule>>() {
                                });

                        boolean removed = rules.removeIf(r -> r.getId().equals(ruleId));
                        if (!removed) {
                            sendError(ctx, 404, "Rule not found");
                            return;
                        }

                        String json = objectMapper.writeValueAsString(rules);
                        redis.set(List.of(ACL_RULES_KEY, json))
                                .onSuccess(v -> sendJson(ctx, new JsonObject()
                                        .put("success", true)
                                        .put("message", "Rule deleted")))
                                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
                    } catch (Exception e) {
                        sendError(ctx, 500, "Failed to delete rule");
                    }
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    // ========== Data Routes API ==========

    /**
     * GET /api/admin/routes
     * Returns all data routes
     */
    public void getRoutes(RoutingContext ctx) {
        List<DataRoute> routes = routeManager.getAllRoutes();
        JsonArray routesArray = new JsonArray();
        for (DataRoute route : routes) {
            routesArray.add(JsonObject.mapFrom(route));
        }
        sendJson(ctx, new JsonObject()
                .put("routes", routesArray)
                .put("total", routes.size())
                .put("enabled", routeManager.getEnabledRouteCount()));
    }

    /**
     * POST /api/admin/routes
     * Adds a new data route
     */
    public void addRoute(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();

        DataRoute route = DataRoute.builder()
                .mqttTopicPattern(body.getString("mqttTopicPattern"))
                .kafkaTopic(body.getString("kafkaTopic"))
                .enabled(body.getBoolean("enabled", true))
                .transformType(DataRoute.TransformType.valueOf(
                        body.getString("transformType", "JSON_WRAP")))
                .description(body.getString("description", ""))
                .build();

        routeManager.saveRoute(route)
                .onSuccess(saved -> sendJson(ctx, new JsonObject()
                        .put("success", true)
                        .put("route", JsonObject.mapFrom(saved))))
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * PUT /api/admin/routes/:routeId
     * Updates a data route
     */
    public void updateRoute(RoutingContext ctx) {
        String routeId = ctx.pathParam("routeId");
        JsonObject body = ctx.body().asJsonObject();

        DataRoute existing = routeManager.getRoute(routeId);
        if (existing == null) {
            sendError(ctx, 404, "Route not found");
            return;
        }

        existing.setMqttTopicPattern(body.getString("mqttTopicPattern", existing.getMqttTopicPattern()));
        existing.setKafkaTopic(body.getString("kafkaTopic", existing.getKafkaTopic()));
        existing.setEnabled(body.getBoolean("enabled", existing.isEnabled()));
        if (body.containsKey("transformType")) {
            existing.setTransformType(DataRoute.TransformType.valueOf(body.getString("transformType")));
        }
        existing.setDescription(body.getString("description", existing.getDescription()));

        routeManager.saveRoute(existing)
                .onSuccess(saved -> sendJson(ctx, new JsonObject()
                        .put("success", true)
                        .put("route", JsonObject.mapFrom(saved))))
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * DELETE /api/admin/routes/:routeId
     * Deletes a data route
     */
    public void deleteRoute(RoutingContext ctx) {
        String routeId = ctx.pathParam("routeId");

        routeManager.deleteRoute(routeId)
                .onSuccess(deleted -> {
                    if (deleted) {
                        sendJson(ctx, new JsonObject()
                                .put("success", true)
                                .put("message", "Route deleted"));
                    } else {
                        sendError(ctx, 404, "Route not found");
                    }
                })
                .onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    /**
     * PUT /api/admin/routes/:routeId/toggle
     * Toggles a route's enabled status
     */
    public void toggleRoute(RoutingContext ctx) {
        String routeId = ctx.pathParam("routeId");

        routeManager.toggleRoute(routeId)
                .onSuccess(route -> sendJson(ctx, new JsonObject()
                        .put("success", true)
                        .put("route", JsonObject.mapFrom(route))))
                .onFailure(err -> sendError(ctx, 404, err.getMessage()));
    }

    /**
     * GET /api/admin/kafka/config
     * Returns current Kafka configuration
     */
    public void getKafkaConfig(RoutingContext ctx) {
        JsonObject kafkaConfig = new JsonObject()
                .put("enabled", config.isKafkaEnabled())
                .put("bootstrapServers", config.getKafkaBootstrapServers())
                .put("topicPrefix", config.getKafkaTopicPrefix())
                .put("producer", new JsonObject()
                        .put("acks", config.getKafkaProducerAcks())
                        .put("retries", config.getKafkaProducerRetries())
                        .put("batchSize", config.getKafkaProducerBatchSize())
                        .put("lingerMs", config.getKafkaProducerLingerMs()));

        sendJson(ctx, kafkaConfig);
    }

    /**
     * GET /api/admin/kafka/status
     * Returns Kafka connection status
     */
    public void getKafkaStatus(RoutingContext ctx) {
        // For now, return basic status - could be enhanced with actual connection check
        JsonObject status = new JsonObject()
                .put("connected", config.isKafkaEnabled())
                .put("bootstrapServers", config.getKafkaBootstrapServers())
                .put("routeCount", routeManager.getRouteCount())
                .put("enabledRoutes", routeManager.getEnabledRouteCount());

        sendJson(ctx, status);
    }

    /**
     * GET /api/admin/metrics/realtime
     * Returns real-time metrics for monitoring dashboard
     */
    public void getRealtimeMetrics(RoutingContext ctx) {
        // Get cluster-wide active connections from Redis (dynamq:connection:* keys)
        redis.keys("dynamq:connection:*")
                .onSuccess(response -> {
                    long clusterActiveConnections = response != null ? response.size()
                            : MetricsVerticle.getActiveConnections();

                    Runtime runtime = Runtime.getRuntime();
                    long totalMemory = runtime.totalMemory();
                    long freeMemory = runtime.freeMemory();
                    long usedMemory = totalMemory - freeMemory;
                    long maxMemory = runtime.maxMemory();

                    JsonObject metrics = new JsonObject()
                            .put("timestamp", Instant.now().toEpochMilli())
                            .put("uptime", MetricsVerticle.getUptimeSeconds())
                            .put("connections", new JsonObject()
                                    .put("active", clusterActiveConnections)
                                    .put("total", MetricsVerticle.getTotalConnections())
                                    .put("rejected", MetricsVerticle.getRejectedConnections()))
                            .put("messages", new JsonObject()
                                    .put("received", MetricsVerticle.getMessagesReceived())
                                    .put("sent", MetricsVerticle.getMessagesSent())
                                    .put("receiveRate", String.format("%.1f", MetricsVerticle.getReceiveRate()))
                                    .put("sendRate", String.format("%.1f", MetricsVerticle.getSendRate())))
                            .put("qos", new JsonObject()
                                    .put("qos0", MetricsVerticle.getMessagesQos0())
                                    .put("qos1", MetricsVerticle.getMessagesQos1())
                                    .put("qos2", MetricsVerticle.getMessagesQos2()))
                            .put("kafka", new JsonObject()
                                    .put("publishSuccess", MetricsVerticle.getKafkaPublishSuccess())
                                    .put("publishFailed", MetricsVerticle.getKafkaPublishFailed()))
                            .put("subscriptions", MetricsVerticle.getSubscriptionCount())
                            .put("memory", new JsonObject()
                                    .put("used", usedMemory)
                                    .put("total", totalMemory)
                                    .put("max", maxMemory)
                                    .put("free", freeMemory)
                                    .put("usedPercent", (int) (usedMemory * 100 / totalMemory)))
                            .put("system", new JsonObject()
                                    .put("availableProcessors", runtime.availableProcessors()))
                            .put("routes", new JsonObject()
                                    .put("total", routeManager.getRouteCount())
                                    .put("enabled", routeManager.getEnabledRouteCount()));

                    sendJson(ctx, metrics);
                })
                .onFailure(err -> {
                    // Fallback to local metrics if Redis fails
                    log.warn("Failed to get cluster connections from Redis, using local count");
                    Runtime runtime = Runtime.getRuntime();
                    long totalMemory = runtime.totalMemory();
                    long freeMemory = runtime.freeMemory();
                    long usedMemory = totalMemory - freeMemory;
                    long maxMemory = runtime.maxMemory();

                    JsonObject metrics = new JsonObject()
                            .put("timestamp", Instant.now().toEpochMilli())
                            .put("uptime", MetricsVerticle.getUptimeSeconds())
                            .put("connections", new JsonObject()
                                    .put("active", MetricsVerticle.getActiveConnections())
                                    .put("total", MetricsVerticle.getTotalConnections())
                                    .put("rejected", MetricsVerticle.getRejectedConnections()))
                            .put("messages", new JsonObject()
                                    .put("received", MetricsVerticle.getMessagesReceived())
                                    .put("sent", MetricsVerticle.getMessagesSent())
                                    .put("receiveRate", String.format("%.1f", MetricsVerticle.getReceiveRate()))
                                    .put("sendRate", String.format("%.1f", MetricsVerticle.getSendRate())))
                            .put("qos", new JsonObject()
                                    .put("qos0", MetricsVerticle.getMessagesQos0())
                                    .put("qos1", MetricsVerticle.getMessagesQos1())
                                    .put("qos2", MetricsVerticle.getMessagesQos2()))
                            .put("kafka", new JsonObject()
                                    .put("publishSuccess", MetricsVerticle.getKafkaPublishSuccess())
                                    .put("publishFailed", MetricsVerticle.getKafkaPublishFailed()))
                            .put("subscriptions", MetricsVerticle.getSubscriptionCount())
                            .put("memory", new JsonObject()
                                    .put("used", usedMemory)
                                    .put("total", totalMemory)
                                    .put("max", maxMemory)
                                    .put("free", freeMemory)
                                    .put("usedPercent", (int) (usedMemory * 100 / totalMemory)))
                            .put("system", new JsonObject()
                                    .put("availableProcessors", runtime.availableProcessors()))
                            .put("routes", new JsonObject()
                                    .put("total", routeManager.getRouteCount())
                                    .put("enabled", routeManager.getEnabledRouteCount()));

                    sendJson(ctx, metrics);
                });
    }

    /**
     * GET /api/admin/health
     * Returns health status for all components
     */
    public void getHealthStatus(RoutingContext ctx) {
        Future<String> redisFuture = redis.ping(List.of())
                .map(r -> "UP")
                .otherwise("DOWN");

        redisFuture.onSuccess(redisStatus -> {
            JsonObject health = new JsonObject()
                    .put("status", "UP")
                    .put("timestamp", Instant.now().toString())
                    .put("components", new JsonObject()
                            .put("redis", new JsonObject()
                                    .put("status", redisStatus)
                                    .put("enabled", config.isRedisEnabled()))
                            .put("kafka", new JsonObject()
                                    .put("status", config.isKafkaEnabled() ? "UP" : "DISABLED")
                                    .put("enabled", config.isKafkaEnabled())
                                    .put("bootstrapServers", config.getKafkaBootstrapServers())));

            sendJson(ctx, health);
        }).onFailure(err -> sendError(ctx, 500, err.getMessage()));
    }

    // ========== Helper Methods ==========

    private Future<Long> getClientCount() {
        return redis.keys(SESSION_KEY_PREFIX + "*")
                .map(keys -> keys != null ? (long) keys.size() : 0L);
    }

    private Future<Long> getNodeCount() {
        return redis.scard(ACTIVE_NODES_KEY)
                .map(response -> response != null ? response.toLong() : 0L);
    }

    private static final String CONNECTION_KEY_PREFIX = "dynamq:connection:";

    private Future<JsonObject> getClientInfo(String clientId) {
        Future<io.vertx.redis.client.Response> sessionFuture = redis.get(SESSION_KEY_PREFIX + clientId);
        Future<io.vertx.redis.client.Response> subsFuture = redis.get(SUBSCRIPTION_KEY_PREFIX + clientId);
        Future<io.vertx.redis.client.Response> connectionFuture = redis
                .exists(java.util.List.of(CONNECTION_KEY_PREFIX + clientId));

        return Future.all(sessionFuture, subsFuture, connectionFuture)
                .map(cf -> {
                    io.vertx.redis.client.Response sessionResponse = sessionFuture.result();
                    io.vertx.redis.client.Response subsResponse = subsFuture.result();
                    io.vertx.redis.client.Response connectionResponse = connectionFuture.result();

                    if (sessionResponse == null)
                        return null;

                    // Check if client is online (connection key exists)
                    boolean isOnline = connectionResponse != null && connectionResponse.toInteger() > 0;

                    // Count subscriptions
                    int subscriptionCount = 0;
                    if (subsResponse != null) {
                        try {
                            Map<String, Integer> subs = objectMapper.readValue(
                                    subsResponse.toString(),
                                    new TypeReference<Map<String, Integer>>() {
                                    });
                            subscriptionCount = subs.size();
                        } catch (Exception e) {
                            // ignore
                        }
                    }

                    try {
                        JsonObject session = new JsonObject(sessionResponse.toString());

                        return new JsonObject()
                                .put("clientId", clientId)
                                .put("username", session.getString("username", ""))
                                .put("connectedAt", parseConnectedAt(session))
                                .put("nodeId", session.getString("nodeId", ""))
                                .put("cleanSession", session.getBoolean("cleanSession", true))
                                .put("subscriptionCount", subscriptionCount)
                                .put("online", isOnline);
                    } catch (Exception e) {
                        return new JsonObject().put("clientId", clientId).put("subscriptionCount", 0).put("online",
                                false);
                    }
                });
    }

    private Future<JsonObject> getClientSubscriptions(String clientId) {
        return redis.get(SUBSCRIPTION_KEY_PREFIX + clientId)
                .map(response -> {
                    JsonObject result = new JsonObject().put("clientId", clientId);
                    if (response != null) {
                        try {
                            Map<String, Integer> subs = objectMapper.readValue(
                                    response.toString(),
                                    new TypeReference<Map<String, Integer>>() {
                                    });
                            JsonArray topics = new JsonArray();
                            for (Map.Entry<String, Integer> entry : subs.entrySet()) {
                                topics.add(new JsonObject()
                                        .put("topic", entry.getKey())
                                        .put("qos", entry.getValue()));
                            }
                            result.put("topics", topics);
                        } catch (Exception e) {
                            result.put("topics", new JsonArray());
                        }
                    } else {
                        result.put("topics", new JsonArray());
                    }
                    return result;
                });
    }

    private Future<JsonObject> getNodeInfo(String nodeId) {
        return redis.get(NODE_HEARTBEAT_KEY_PREFIX + nodeId)
                .map(response -> {
                    JsonObject node = new JsonObject()
                            .put("nodeId", nodeId)
                            .put("status", response != null ? "online" : "offline");

                    if (response != null) {
                        try {
                            long timestamp = Long.parseLong(response.toString());
                            node.put("lastHeartbeat", Instant.ofEpochMilli(timestamp).toString());
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    return node;
                });
    }

    private String getUptime() {
        // This would be calculated from application start time
        return "N/A";
    }

    private void sendJson(RoutingContext ctx, JsonObject json) {
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(json.encode());
    }

    private void sendError(RoutingContext ctx, int status, String message) {
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                        .put("error", true)
                        .put("message", message)
                        .encode());
    }
}
