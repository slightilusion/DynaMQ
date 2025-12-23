package org.dynabot.acl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Redis-backed ACL provider for distributed deployments.
 * Stores ACL rules in Redis and supports dynamic updates.
 */
@Slf4j
public class RedisAclProvider implements AclProvider {

    private static final String ACL_RULES_KEY = "dynamq:acl:rules";
    private static final long REFRESH_INTERVAL_MS = 30000; // 30 seconds

    private final Vertx vertx;
    private final RedisAPI redis;
    private final ObjectMapper objectMapper;
    private final List<AclRule> rules = new CopyOnWriteArrayList<>();
    private final boolean defaultAllow;

    private Long refreshTimerId;

    public RedisAclProvider(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.defaultAllow = config.isAclDefaultAllow();
        this.objectMapper = new ObjectMapper();

        // Create Redis client
        RedisOptions options = new RedisOptions()
                .setConnectionString(config.getRedisConnectionString())
                .setMaxPoolSize(config.getRedisMaxPoolSize());

        Redis client = Redis.createClient(vertx, options);
        this.redis = RedisAPI.api(client);

        // Initial load
        reload().onComplete(ar -> {
            if (ar.succeeded()) {
                log.info("RedisAclProvider initialized with {} rules", rules.size());
            } else {
                log.error("Failed to initialize RedisAclProvider", ar.cause());
            }
        });

        // Schedule periodic refresh
        refreshTimerId = vertx.setPeriodic(REFRESH_INTERVAL_MS, id -> reload());
    }

    @Override
    public Future<Boolean> checkPermission(String clientId, String username, String action, String topic) {
        for (AclRule rule : rules) {
            if (rule.matches(clientId, username, action, topic)) {
                log.debug("ACL matched rule {}: client={}, action={}, topic={}, allow={}",
                        rule.getId(), clientId, action, topic, rule.isAllow());
                return Future.succeededFuture(rule.isAllow());
            }
        }

        log.debug("ACL no matching rule: client={}, action={}, topic={}, defaultAllow={}",
                clientId, action, topic, defaultAllow);
        return Future.succeededFuture(defaultAllow);
    }

    @Override
    public Future<Void> reload() {
        return redis.get(ACL_RULES_KEY)
                .map(response -> {
                    if (response != null) {
                        try {
                            List<AclRule> loadedRules = objectMapper.readValue(
                                    response.toString(),
                                    new TypeReference<List<AclRule>>() {
                                    });
                            loadedRules.sort(Comparator.comparingInt(AclRule::getPriority).reversed());
                            rules.clear();
                            rules.addAll(loadedRules);
                            log.debug("Reloaded {} ACL rules from Redis", rules.size());
                        } catch (Exception e) {
                            log.error("Failed to parse ACL rules from Redis", e);
                        }
                    }
                    return (Void) null;
                })
                .recover(err -> {
                    log.error("Failed to load ACL rules from Redis", err);
                    return Future.succeededFuture();
                });
    }

    @Override
    public Future<Void> addRule(AclRule rule) {
        if (rule.getId() == null || rule.getId().isEmpty()) {
            rule.setId(UUID.randomUUID().toString());
        }

        rules.add(rule);
        rules.sort(Comparator.comparingInt(AclRule::getPriority).reversed());

        return saveRules()
                .onSuccess(v -> log.info("Added ACL rule: {}", rule.getId()))
                .onFailure(err -> log.error("Failed to save ACL rule", err));
    }

    @Override
    public Future<Void> removeRule(String ruleId) {
        rules.removeIf(r -> r.getId().equals(ruleId));

        return saveRules()
                .onSuccess(v -> log.info("Removed ACL rule: {}", ruleId))
                .onFailure(err -> log.error("Failed to save ACL rules after removal", err));
    }

    private Future<Void> saveRules() {
        try {
            String json = objectMapper.writeValueAsString(new ArrayList<>(rules));
            return redis.set(List.of(ACL_RULES_KEY, json)).mapEmpty();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Stop the periodic refresh
     */
    public void stop() {
        if (refreshTimerId != null) {
            vertx.cancelTimer(refreshTimerId);
            refreshTimerId = null;
        }
    }
}
