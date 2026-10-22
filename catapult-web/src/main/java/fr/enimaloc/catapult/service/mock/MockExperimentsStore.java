package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentStatus;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentVariantDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The mock's experiments: one store for the whole application. Answers like catapult-api: 204 for
 * every action, 404 for an unknown experiment, rule, override, or a forced variant of another
 * experiment (or a USER override on an unknown login), 409 for a status change the lifecycle
 * refuses (activate from DRAFT or PAUSED, pause from ACTIVE, end from ACTIVE or PAUSED).
 */
class MockExperimentsStore {
    private static final class Experiment {
        final UUID id = UUID.randomUUID();
        final String key;
        final String name;
        final String description;
        ExperimentStatus status;
        final List<ExperimentVariantDto> variants = new ArrayList<>();
        final List<ExperimentRuleDto> rules = new ArrayList<>();
        final List<ExperimentOverrideDto> overrides = new ArrayList<>();

        Experiment(String key, String name, String description, ExperimentStatus status) {
            this.key = key;
            this.name = name;
            this.description = description;
            this.status = status;
        }

        void variant(String key, String name, int weight, boolean control) {
            variants.add(new ExperimentVariantDto(UUID.randomUUID(), key, name, weight, control));
        }

        ExperimentSummaryDto summary() {
            return new ExperimentSummaryDto(id, key, name, status, List.copyOf(variants), rules.size());
        }

        ExperimentSettingsDto settings() {
            return new ExperimentSettingsDto(id, key, name, description, status,
                    List.copyOf(variants), List.copyOf(rules), List.copyOf(overrides));
        }
    }

    private final List<Experiment> experiments = new ArrayList<>();

    MockExperimentsStore() {
        Experiment minecraft = new Experiment("minecraft.integration", "Minecraft integration",
                "Offers the Minecraft linking to the members", ExperimentStatus.DRAFT);
        minecraft.variant("control", "Control", 0, true);
        minecraft.variant("enabled", "Enabled", 100, false);
        minecraft.rules.add(new ExperimentRuleDto(UUID.randomUUID(), "RANDOM", 100, null, null, null));
        experiments.add(minecraft);

        Experiment dashboard = new Experiment("new-dashboard", "New dashboard",
                "A redesigned dashboard for half of the members", ExperimentStatus.ACTIVE);
        dashboard.variant("control", "Control", 50, true);
        dashboard.variant("new", "New dashboard", 50, false);
        experiments.add(dashboard);
    }

    synchronized List<ExperimentSummaryDto> list() {
        return experiments.stream().map(Experiment::summary).toList();
    }

    synchronized Optional<ExperimentSettingsDto> find(UUID id) {
        Experiment experiment = byId(id);
        return experiment == null ? Optional.empty() : Optional.of(experiment.settings());
    }

    /** "activate", "pause", "end" change the status; "reassign" only answers, the mock has no assignments. */
    synchronized int action(UUID id, String action) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        ExperimentStatus status = experiment.status;
        switch (action) {
            case "activate" -> {
                if (status != ExperimentStatus.DRAFT && status != ExperimentStatus.PAUSED) return 409;
                experiment.status = ExperimentStatus.ACTIVE;
            }
            case "pause" -> {
                if (status != ExperimentStatus.ACTIVE) return 409;
                experiment.status = ExperimentStatus.PAUSED;
            }
            case "end" -> {
                if (status == ExperimentStatus.DRAFT || status == ExperimentStatus.ENDED) return 409;
                experiment.status = ExperimentStatus.ENDED;
            }
            case "reassign" -> { }
            default -> { return 404; }
        }
        return 204;
    }

    /** A variant missing from the map keeps its weight. */
    synchronized int setWeights(UUID id, Map<UUID, Integer> weights) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        experiment.variants.replaceAll(v -> weights.containsKey(v.id())
                ? new ExperimentVariantDto(v.id(), v.key(), v.name(), weights.get(v.id()), v.control())
                : v);
        return 204;
    }

    synchronized int addRule(UUID id, ExperimentRuleRequest rule) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        experiment.rules.add(new ExperimentRuleDto(UUID.randomUUID(), rule.ruleType(), rule.percentage(),
                rule.attributeKey(), rule.attributeOperator(), rule.attributeValue()));
        return 204;
    }

    synchronized int deleteRule(UUID id, UUID ruleId) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        return experiment.rules.removeIf(r -> r.id().equals(ruleId)) ? 204 : 404;
    }

    /** {@code userKnown}: whether a USER override's login belongs to a member; the store has no members to ask. */
    synchronized int addOverride(UUID id, ExperimentOverrideRequest override, boolean userKnown) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        if ("USER".equals(override.overrideType()) && !userKnown) return 404;
        UUID target = override.targetVariantId();
        if (target != null && experiment.variants.stream().noneMatch(v -> v.id().equals(target))) return 404;
        experiment.overrides.add(new ExperimentOverrideDto(UUID.randomUUID(), override.overrideType(), override.action(),
                override.priority(), override.twitchUsername(), override.attributeKey(), override.attributeOp(),
                override.attributeVal(), target));
        return 204;
    }

    synchronized int deleteOverride(UUID id, UUID overrideId) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        return experiment.overrides.removeIf(o -> o.id().equals(overrideId)) ? 204 : 404;
    }

    private Experiment byId(UUID id) {
        return experiments.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }
}
