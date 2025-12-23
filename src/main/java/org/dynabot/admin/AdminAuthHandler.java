package org.dynabot.admin;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

/**
 * Admin API Authentication Handler.
 * Validates API Key from X-API-Key header or api-key query parameter.
 */
@Slf4j
public class AdminAuthHandler implements Handler<RoutingContext> {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "api-key";

    private final boolean enabled;
    private final String apiKey;

    public AdminAuthHandler(AppConfig config) {
        this.enabled = config.isAdminAuthEnabled();
        this.apiKey = config.getAdminApiKey();

        if (enabled) {
            log.info("Admin API authentication enabled");
        } else {
            log.warn("Admin API authentication DISABLED - API is publicly accessible");
        }
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (!enabled) {
            ctx.next();
            return;
        }

        // Check API key from header
        String providedKey = ctx.request().getHeader(API_KEY_HEADER);

        // Fall back to query parameter
        if (providedKey == null || providedKey.isEmpty()) {
            providedKey = ctx.request().getParam(API_KEY_PARAM);
        }

        if (providedKey == null || providedKey.isEmpty()) {
            log.warn("Admin API access denied - no API key provided from {}",
                    ctx.request().remoteAddress());
            ctx.response()
                    .setStatusCode(401)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("error", true)
                            .put("message", "API key required. Provide via X-API-Key header")
                            .encode());
            return;
        }

        if (!apiKey.equals(providedKey)) {
            log.warn("Admin API access denied - invalid API key from {}",
                    ctx.request().remoteAddress());
            ctx.response()
                    .setStatusCode(403)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("error", true)
                            .put("message", "Invalid API key")
                            .encode());
            return;
        }

        // API key valid, continue to next handler
        ctx.next();
    }
}
