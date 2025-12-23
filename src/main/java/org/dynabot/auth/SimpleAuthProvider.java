package org.dynabot.auth;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple in-memory auth provider using config file credentials.
 */
@Slf4j
public class SimpleAuthProvider implements AuthProvider {

    private final Map<String, String> users = new HashMap<>();

    public SimpleAuthProvider(AppConfig config) {
        loadUsers(config);
    }

    private void loadUsers(AppConfig config) {
        JsonObject dynamq = config.getConfig().getJsonObject("dynamq", new JsonObject());
        JsonObject auth = dynamq.getJsonObject("auth", new JsonObject());
        JsonObject simple = auth.getJsonObject("simple", new JsonObject());
        JsonArray usersArray = simple.getJsonArray("users", new JsonArray());

        for (int i = 0; i < usersArray.size(); i++) {
            JsonObject user = usersArray.getJsonObject(i);
            String username = user.getString("username");
            String password = user.getString("password");
            if (username != null && password != null) {
                users.put(username, password);
            }
        }

        log.info("Loaded {} users from configuration", users.size());
    }

    @Override
    public Future<AuthResult> authenticate(String clientId, String username, String password) {
        if (username == null || password == null) {
            log.debug("Authentication failed for client {}: missing credentials", clientId);
            return Future.succeededFuture(AuthResult.failed("Missing username or password"));
        }

        String storedPassword = users.get(username);
        if (storedPassword == null) {
            log.debug("Authentication failed for client {}: unknown user {}", clientId, username);
            return Future.succeededFuture(AuthResult.failed("Unknown user"));
        }

        if (!storedPassword.equals(password)) {
            log.debug("Authentication failed for client {}: wrong password", clientId);
            return Future.succeededFuture(AuthResult.failed("Invalid password"));
        }

        log.debug("Authentication successful for client {} as user {}", clientId, username);
        return Future.succeededFuture(AuthResult.success(username));
    }

    @Override
    public Future<Boolean> authorize(String clientId, String action, String topic) {
        // Simple provider allows all actions for authenticated users
        return Future.succeededFuture(true);
    }
}
