package org.dynabot.acl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.dynabot.config.AppConfig;

/**
 * Interface for ACL (Access Control List) providers.
 * Implementations can load ACL rules from various backends.
 */
public interface AclProvider {

    /**
     * ACL actions
     */
    String ACTION_CONNECT = "connect";
    String ACTION_PUBLISH = "publish";
    String ACTION_SUBSCRIBE = "subscribe";

    /**
     * Create an ACL provider based on configuration
     */
    static AclProvider create(Vertx vertx, AppConfig config) {
        if (!config.isAclEnabled()) {
            return new NoOpAclProvider();
        }

        String provider = config.getAclProvider();
        return switch (provider) {
            case "redis" -> new RedisAclProvider(vertx, config);
            case "simple" -> new SimpleAclProvider(config);
            default -> {
                throw new IllegalArgumentException("Unknown ACL provider: " + provider);
            }
        };
    }

    /**
     * Check if a client is allowed to perform an action
     *
     * @param clientId Client identifier
     * @param username Username (may be null)
     * @param action   Action to check (connect, publish, subscribe)
     * @param topic    Topic for publish/subscribe (null for connect)
     * @return Future containing true if allowed
     */
    Future<Boolean> checkPermission(String clientId, String username, String action, String topic);

    /**
     * Reload ACL rules from storage
     *
     * @return Future completing when reload is done
     */
    Future<Void> reload();

    /**
     * Add a new ACL rule dynamically
     *
     * @param rule Rule to add
     * @return Future completing when rule is added
     */
    Future<Void> addRule(AclRule rule);

    /**
     * Remove an ACL rule
     *
     * @param ruleId Rule ID to remove
     * @return Future completing when rule is removed
     */
    Future<Void> removeRule(String ruleId);
}
