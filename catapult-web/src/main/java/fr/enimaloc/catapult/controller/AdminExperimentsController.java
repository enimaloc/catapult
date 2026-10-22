package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The admin Experiments tab's actions (lifecycle, weights, rules, overrides), forwarded to catapult-api
 * with the session's JWT and answered with its status. The tab posts friendly bodies (a group key, a tag);
 * they are mapped here to the attribute-based requests catapult-api expects.
 */
@RestController
@RequestMapping("/admin/experiments")
@RequiredArgsConstructor
public class AdminExperimentsController {
    private static final Set<String> ACTIONS = Set.of("activate", "pause", "end", "reassign");
    private static final Set<String> TAG_OPERATORS = Set.of("exists", "eq");
    private static final Set<String> OVERRIDE_ACTIONS = Set.of("FORCE_INCLUDE", "FORCE_EXCLUDE", "FORCE_VARIANT");

    private final ApiService apiService;
    private final ModelFiller filler;

    public record RuleBody(String type, Integer percentage, String attributeKey, String attributeOperator,
                           String attributeValue, String groupKey, String experimentKey, String variantKey) {}
    public record OverrideBody(String target, String twitchUsername, String tagKey, String tagValue,
                               String groupKey, String action, Integer priority, UUID variantId) {}

    @PostMapping("/{id}/{action}")
    public ResponseEntity<Void> action(@PathVariable UUID id, @PathVariable String action) {
        if (!ACTIONS.contains(action)) {
            AdminAccess.require(filler);
            return ResponseEntity.notFound().build();
        }
        return AdminAccess.relay(filler, () -> apiService.adminExperimentAction(id, action));
    }

    @PostMapping("/{id}/weights")
    public ResponseEntity<Void> weights(@PathVariable UUID id, @RequestBody Map<UUID, Integer> weights) {
        boolean negative = weights.values().stream().anyMatch(w -> w == null || w < 0);
        // a long: a few large weights must not wrap around to a valid-looking sum
        long sum = negative ? 0 : weights.values().stream().mapToLong(Integer::longValue).sum();
        if (negative || sum == 0) {
            return invalid();
        }
        return AdminAccess.relay(filler, () -> apiService.adminExperimentWeights(id, weights));
    }

    @PostMapping("/{id}/rules")
    public ResponseEntity<Void> addRule(@PathVariable UUID id, @RequestBody RuleBody body) {
        ExperimentRuleRequest rule = toRule(body);
        if (rule == null) {
            return invalid();
        }
        return AdminAccess.relay(filler, () -> apiService.adminAddExperimentRule(id, rule));
    }

    @PostMapping("/{id}/rules/{ruleId}/delete")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id, @PathVariable UUID ruleId) {
        return AdminAccess.relay(filler, () -> apiService.adminDeleteExperimentRule(id, ruleId));
    }

    @PostMapping("/{id}/overrides")
    public ResponseEntity<Void> addOverride(@PathVariable UUID id, @RequestBody OverrideBody body) {
        ExperimentOverrideRequest override = toOverride(body);
        if (override == null) {
            return invalid();
        }
        return AdminAccess.relay(filler, () -> apiService.adminAddExperimentOverride(id, override));
    }

    @PostMapping("/{id}/overrides/{overrideId}/delete")
    public ResponseEntity<Void> deleteOverride(@PathVariable UUID id, @PathVariable UUID overrideId) {
        return AdminAccess.relay(filler, () -> apiService.adminDeleteExperimentOverride(id, overrideId));
    }

    /** A refused body is still only answered to an admin, as for every other local answer. */
    private ResponseEntity<Void> invalid() {
        AdminAccess.require(filler);
        return ResponseEntity.badRequest().build();
    }

    /** The rule catapult-api expects, or null when the body does not describe a valid one. */
    private static ExperimentRuleRequest toRule(RuleBody b) {
        String type = trim(b.type());
        if (type == null) {
            return null;
        }
        switch (type) {
            case "RANDOM":
                Integer pct = b.percentage();
                return pct == null || pct < 0 || pct > 100 ? null : new ExperimentRuleRequest(type, 0, pct, null, null, null);
            case "GROUP": {
                String group = trim(b.groupKey());
                return group == null ? null : new ExperimentRuleRequest(type, 0, null, "group", "in", group);
            }
            case "ATTRIBUTE": {
                String key = trim(b.attributeKey());
                String op = trim(b.attributeOperator());
                String value = trim(b.attributeValue());
                // "exists" is the one operator that takes no value
                boolean needsValue = !"exists".equals(op);
                return key == null || op == null || !TAG_OPERATORS.contains(op) || (needsValue && value == null)
                        ? null : new ExperimentRuleRequest(type, 0, null, key, op, value);
            }
            case "EXPERIMENT": {
                String experiment = trim(b.experimentKey());
                String variant = trim(b.variantKey());
                return experiment == null || variant == null
                        ? null : new ExperimentRuleRequest(type, 0, null, experiment, "eq", variant);
            }
            case "MANUAL":
                return new ExperimentRuleRequest(type, 0, null, null, null, null);
            default:
                return null;
        }
    }

    /** The override catapult-api expects, or null when the body does not describe a valid one. */
    private static ExperimentOverrideRequest toOverride(OverrideBody b) {
        String target = trim(b.target());
        String action = trim(b.action());
        if (target == null || action == null || !OVERRIDE_ACTIONS.contains(action)) {
            return null;
        }
        UUID variant = null;
        if ("FORCE_VARIANT".equals(action)) {
            variant = b.variantId();
            if (variant == null) {
                return null;
            }
        }
        switch (target) {
            case "account": {
                String login = trim(b.twitchUsername());
                return login == null ? null
                        : new ExperimentOverrideRequest("USER", action, priority(b), login, null, null, null, variant);
            }
            case "tag": {
                String key = trim(b.tagKey());
                String value = trim(b.tagValue());
                if (key == null) {
                    return null;
                }
                return new ExperimentOverrideRequest("ATTRIBUTE", action, priority(b), null, "flag:" + key,
                        value == null ? "exists" : "eq", value, variant);
            }
            case "group": {
                String group = trim(b.groupKey());
                return group == null ? null
                        : new ExperimentOverrideRequest("ATTRIBUTE", action, priority(b), null, "group", "in", group, variant);
            }
            default:
                return null;
        }
    }

    /** A missing priority is 0 (a boxed field, so an omitted one does not fail the binding before the admin check). */
    private static int priority(OverrideBody b) {
        return b.priority() == null ? 0 : b.priority();
    }

    /** The trimmed text, or null when it is missing or blank. */
    private static String trim(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
