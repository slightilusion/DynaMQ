package org.dynabot.admin;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Admin API Verticle.
 * Provides REST API for management UI.
 */
@Slf4j
public class AdminApiVerticle extends AbstractVerticle {

    private HttpServer httpServer;
    private AppConfig appConfig;
    private AdminController adminController;

    @Override
    public void start(Promise<Void> startPromise) {
        appConfig = new AppConfig(config());

        int port = config().getInteger("admin.port", 8080);

        // Create admin controller
        adminController = new AdminController(vertx, appConfig);

        // Create router
        Router router = createRouter();

        // Create HTTP server
        HttpServerOptions options = new HttpServerOptions()
                .setPort(port)
                .setHost("0.0.0.0");

        httpServer = vertx.createHttpServer(options);
        httpServer.requestHandler(router);

        httpServer.listen()
                .onSuccess(server -> {
                    log.info("Admin API started on port {}", server.actualPort());
                    startPromise.complete();
                })
                .onFailure(err -> {
                    log.error("Failed to start Admin API", err);
                    startPromise.fail(err);
                });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (httpServer != null) {
            httpServer.close()
                    .onComplete(ar -> {
                        log.info("Admin API stopped");
                        stopPromise.complete();
                    });
        } else {
            stopPromise.complete();
        }
    }

    private Router createRouter() {
        Router router = Router.router(vertx);

        // CORS handler
        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("Authorization");
        allowedHeaders.add("X-Requested-With");
        allowedHeaders.add("X-API-Key"); // For admin API authentication

        Set<HttpMethod> allowedMethods = new HashSet<>();
        allowedMethods.add(HttpMethod.GET);
        allowedMethods.add(HttpMethod.POST);
        allowedMethods.add(HttpMethod.PUT);
        allowedMethods.add(HttpMethod.DELETE);
        allowedMethods.add(HttpMethod.OPTIONS);

        router.route().handler(CorsHandler.create()
                .addOrigin("*")
                .allowedHeaders(allowedHeaders)
                .allowedMethods(allowedMethods));

        // Body handler for JSON parsing
        router.route().handler(BodyHandler.create());

        // Health check (no auth required)
        router.get("/health").handler(ctx -> ctx.response().end("{\"status\":\"UP\"}"));

        // Admin API authentication handler
        AdminAuthHandler authHandler = new AdminAuthHandler(appConfig);
        router.route("/api/admin/*").handler(authHandler);

        // Global failure handler for unified error responses
        router.route("/api/*").failureHandler(ctx -> {
            Throwable failure = ctx.failure();
            int statusCode = ctx.statusCode();

            if (statusCode < 400) {
                statusCode = 500;
            }

            String message = failure != null ? failure.getMessage() : "Unknown error";
            log.error("API error: {} - {}", statusCode, message, failure);

            ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"error\":true,\"message\":\"" + message.replace("\"", "'") + "\"}");
        });

        // Admin API routes
        router.get("/api/admin/dashboard").handler(adminController::getDashboard);
        router.get("/api/admin/clients").handler(adminController::getClients);
        router.get("/api/admin/clients/:clientId").handler(adminController::getClientDetail);
        router.delete("/api/admin/clients/:clientId").handler(adminController::kickClient);
        router.get("/api/admin/subscriptions").handler(adminController::getSubscriptions);
        router.get("/api/admin/cluster/nodes").handler(adminController::getClusterNodes);
        router.get("/api/admin/acl/rules").handler(adminController::getAclRules);
        router.post("/api/admin/acl/rules").handler(adminController::addAclRule);
        router.delete("/api/admin/acl/rules/:ruleId").handler(adminController::deleteAclRule);

        // Data Routes API
        router.get("/api/admin/routes").handler(adminController::getRoutes);
        router.post("/api/admin/routes").handler(adminController::addRoute);
        router.put("/api/admin/routes/:routeId").handler(adminController::updateRoute);
        router.delete("/api/admin/routes/:routeId").handler(adminController::deleteRoute);
        router.put("/api/admin/routes/:routeId/toggle").handler(adminController::toggleRoute);

        // Kafka Config API
        router.get("/api/admin/kafka/config").handler(adminController::getKafkaConfig);
        router.get("/api/admin/kafka/status").handler(adminController::getKafkaStatus);

        // Metrics & Health API
        router.get("/api/admin/metrics/realtime").handler(adminController::getRealtimeMetrics);
        router.get("/api/admin/health").handler(adminController::getHealthStatus);

        log.info("Admin API routes registered");
        return router;
    }
}
