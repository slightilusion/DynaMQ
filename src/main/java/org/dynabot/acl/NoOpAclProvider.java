package org.dynabot.acl;

import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

/**
 * No-op ACL provider that allows all operations.
 * Used when ACL is disabled.
 */
@Slf4j
public class NoOpAclProvider implements AclProvider {

    public NoOpAclProvider() {
        log.info("ACL disabled - all operations allowed");
    }

    @Override
    public Future<Boolean> checkPermission(String clientId, String username, String action, String topic) {
        return Future.succeededFuture(true);
    }

    @Override
    public Future<Void> reload() {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> addRule(AclRule rule) {
        log.warn("ACL is disabled, rule not added: {}", rule.getId());
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> removeRule(String ruleId) {
        log.warn("ACL is disabled, rule not removed: {}", ruleId);
        return Future.succeededFuture();
    }
}
