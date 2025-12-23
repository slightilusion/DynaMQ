package org.dynabot.auth;

import io.vertx.core.Future;

/**
 * No-op auth provider that always allows access.
 * Used when authentication is disabled.
 */
public class NoOpAuthProvider implements AuthProvider {

    @Override
    public Future<AuthResult> authenticate(String clientId, String username, String password) {
        // Always succeed
        return Future.succeededFuture(AuthResult.success(
                username != null ? username : clientId));
    }

    @Override
    public Future<Boolean> authorize(String clientId, String action, String topic) {
        // Always allow
        return Future.succeededFuture(true);
    }
}
