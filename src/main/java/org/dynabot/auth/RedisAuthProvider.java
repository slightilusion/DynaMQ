package org.dynabot.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.List;

/**
 * Redis-based auth provider for distributed authentication.
 * Stores user credentials in Redis with format: dynamq:user:{username} ->
 * password_hash
 */
@Slf4j
public class RedisAuthProvider implements AuthProvider {

    private static final String USER_KEY_PREFIX = "dynamq:user:";

    private final RedisAPI redis;

    public RedisAuthProvider(Vertx vertx, AppConfig config) {
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize());

        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        log.info("Redis auth provider initialized");
    }

    @Override
    public Future<AuthResult> authenticate(String clientId, String username, String password) {
        if (username == null || password == null) {
            return Future.succeededFuture(AuthResult.failed("Missing username or password"));
        }

        String key = USER_KEY_PREFIX + username;

        return redis.get(key)
                .map(response -> {
                    if (response == null) {
                        log.debug("Authentication failed for {}: user not found", username);
                        return AuthResult.failed("Unknown user");
                    }

                    String storedPassword = response.toString();
                    if (!storedPassword.equals(password)) {
                        log.debug("Authentication failed for {}: wrong password", username);
                        return AuthResult.failed("Invalid password");
                    }

                    log.debug("Authentication successful for {} as {}", clientId, username);
                    return AuthResult.success(username);
                })
                .otherwise(err -> {
                    log.error("Redis auth error: {}", err.getMessage());
                    return AuthResult.failed("Authentication error");
                });
    }

    @Override
    public Future<Boolean> authorize(String clientId, String action, String topic) {
        // For now, allow all for authenticated users
        // Can be extended to check ACLs in Redis
        return Future.succeededFuture(true);
    }

    /**
     * Add a user (utility method for admin)
     */
    public Future<Void> addUser(String username, String password) {
        String key = USER_KEY_PREFIX + username;
        return redis.set(List.of(key, password))
                .mapEmpty();
    }

    /**
     * Remove a user
     */
    public Future<Void> removeUser(String username) {
        String key = USER_KEY_PREFIX + username;
        return redis.del(List.of(key))
                .mapEmpty();
    }
}
