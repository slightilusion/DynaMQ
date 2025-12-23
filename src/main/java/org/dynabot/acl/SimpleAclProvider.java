package org.dynabot.acl;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in-memory ACL provider using config file rules.
 */
@Slf4j
public class SimpleAclProvider implements AclProvider {

    private final List<AclRule> rules = new CopyOnWriteArrayList<>();
    private final boolean defaultAllow;

    public SimpleAclProvider(AppConfig config) {
        this.defaultAllow = config.isAclDefaultAllow();
        loadRules(config);
    }

    private void loadRules(AppConfig config) {
        JsonObject dynamq = config.getConfig().getJsonObject("dynamq", new JsonObject());
        JsonObject acl = dynamq.getJsonObject("acl", new JsonObject());
        JsonArray rulesArray = acl.getJsonArray("rules", new JsonArray());

        List<AclRule> loadedRules = new ArrayList<>();
        for (int i = 0; i < rulesArray.size(); i++) {
            JsonObject ruleJson = rulesArray.getJsonObject(i);
            AclRule rule = AclRule.builder()
                    .id(ruleJson.getString("id", "rule-" + i))
                    .clientIdPattern(ruleJson.getString("clientId", "*"))
                    .usernamePattern(ruleJson.getString("username", "*"))
                    .action(ruleJson.getString("action", "*"))
                    .topicPattern(ruleJson.getString("topic", "#"))
                    .allow(ruleJson.getBoolean("allow", true))
                    .priority(ruleJson.getInteger("priority", 0))
                    .build();
            loadedRules.add(rule);
        }

        // Sort by priority (descending)
        loadedRules.sort(Comparator.comparingInt(AclRule::getPriority).reversed());

        rules.clear();
        rules.addAll(loadedRules);

        log.info("Loaded {} ACL rules from configuration, defaultAllow={}", rules.size(), defaultAllow);
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

        // No matching rule, use default
        log.debug("ACL no matching rule: client={}, action={}, topic={}, defaultAllow={}",
                clientId, action, topic, defaultAllow);
        return Future.succeededFuture(defaultAllow);
    }

    @Override
    public Future<Void> reload() {
        log.info("SimpleAclProvider does not support dynamic reload");
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> addRule(AclRule rule) {
        rules.add(rule);
        rules.sort(Comparator.comparingInt(AclRule::getPriority).reversed());
        log.info("Added ACL rule: {}", rule.getId());
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> removeRule(String ruleId) {
        rules.removeIf(r -> r.getId().equals(ruleId));
        log.info("Removed ACL rule: {}", ruleId);
        return Future.succeededFuture();
    }
}
