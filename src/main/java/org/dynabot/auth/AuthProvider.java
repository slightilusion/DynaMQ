package org.dynabot.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.dynabot.config.AppConfig;

/**
 * Interface for authenticating MQTT clients.
 * Implementations can use various backends: simple config, Redis, LDAP, etc.
 */
public interface AuthProvider {

    /**
     * Create an auth provider based on configuration
     */
    static AuthProvider create(Vertx vertx, AppConfig config) {
        if (!config.isAuthEnabled()) {
            return new NoOpAuthProvider();
        }

        String provider = config.getAuthProvider();
        return switch (provider) {
            case "simple" -> new SimpleAuthProvider(config);
            case "redis" -> new RedisAuthProvider(vertx, config);
            default -> {
                throw new IllegalArgumentException("Unknown auth provider: " + provider);
            }
        };
    }

    /**
     * Authenticate a client
     *
     * @param clientId Client identifier
     * @param username Username (may be null)
     * @param password Password (may be null)
     * @return Future containing auth result
     */
    Future<AuthResult> authenticate(String clientId, String username, String password);

    /**
     * Check if a client is authorized to perform an action
     *
     * @param clientId Client identifier
     * @param action   Action to check (connect, publish, subscribe)
     * @param topic    Topic for publish/subscribe actions
     * @return Future containing true if authorized
     */
    Future<Boolean> authorize(String clientId, String action, String topic);
}
