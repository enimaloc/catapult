package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentStatus;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockExperimentsStoreTest {
    private final MockExperimentsStore store = new MockExperimentsStore();

    private UUID idOf(String key) {
        return store.list().stream().filter(e -> e.key().equals(key)).map(ExperimentSummaryDto::id).findFirst().orElseThrow();
    }

    private ExperimentSettingsDto settings(String key) {
        return store.find(idOf(key)).orElseThrow();
    }

    @Test
    void isSeededWithADraftAndAnActiveExperiment() {
        ExperimentSettingsDto draft = settings("minecraft.integration");
        assertThat(draft.status()).isEqualTo(ExperimentStatus.DRAFT);
        assertThat(draft.variants()).extracting("key", "weight").containsExactly(
                org.assertj.core.groups.Tuple.tuple("control", 0), org.assertj.core.groups.Tuple.tuple("enabled", 100));
        assertThat(draft.rules()).hasSize(1);
        assertThat(draft.rules().get(0).type()).isEqualTo("RANDOM");
        assertThat(draft.rules().get(0).percentage()).isEqualTo(100);

        ExperimentSettingsDto active = settings("new-dashboard");
        assertThat(active.status()).isEqualTo(ExperimentStatus.ACTIVE);
        assertThat(active.rules()).isEmpty();
        assertThat(store.list()).extracting("ruleCount").containsExactly(1, 0);
    }

    @Test
    void transitions_followTheLifecycle() {
        UUID id = idOf("minecraft.integration");

        assertThat(store.action(id, "activate")).isEqualTo(204);
        assertThat(settings("minecraft.integration").status()).isEqualTo(ExperimentStatus.ACTIVE);
        assertThat(store.action(id, "activate")).isEqualTo(409);
        assertThat(store.action(id, "pause")).isEqualTo(204);
        assertThat(settings("minecraft.integration").status()).isEqualTo(ExperimentStatus.PAUSED);
        assertThat(store.action(id, "pause")).isEqualTo(409);
        assertThat(store.action(id, "activate")).isEqualTo(204);
        assertThat(store.action(id, "end")).isEqualTo(204);
        assertThat(settings("minecraft.integration").status()).isEqualTo(ExperimentStatus.ENDED);
        assertThat(store.action(id, "end")).isEqualTo(409);
        assertThat(store.action(id, "activate")).isEqualTo(409);
        assertThat(store.action(id, "pause")).isEqualTo(409);
    }

    @Test
    void aDraftCanNotBePausedNorEnded() {
        UUID id = idOf("minecraft.integration");

        assertThat(store.action(id, "pause")).isEqualTo(409);
        assertThat(store.action(id, "end")).isEqualTo(409);
    }

    @Test
    void reassign_andUnknownExperiment() {
        assertThat(store.action(idOf("new-dashboard"), "reassign")).isEqualTo(204);
        assertThat(store.action(UUID.randomUUID(), "activate")).isEqualTo(404);
        assertThat(store.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void weights_areSaved() {
        ExperimentSettingsDto before = settings("new-dashboard");
        UUID control = before.variants().get(0).id();
        UUID other = before.variants().get(1).id();

        assertThat(store.setWeights(before.id(), Map.of(control, 20, other, 80))).isEqualTo(204);

        assertThat(settings("new-dashboard").variants()).extracting("weight").containsExactly(20, 80);
        assertThat(store.setWeights(UUID.randomUUID(), Map.of())).isEqualTo(404);
    }

    @Test
    void rules_areAddedAndDeleted() {
        ExperimentSettingsDto exp = settings("new-dashboard");

        assertThat(store.addRule(exp.id(), new ExperimentRuleRequest("RANDOM", 0, 30, null, null, null))).isEqualTo(204);

        var rules = settings("new-dashboard").rules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).percentage()).isEqualTo(30);
        assertThat(store.deleteRule(exp.id(), rules.get(0).id())).isEqualTo(204);
        assertThat(settings("new-dashboard").rules()).isEmpty();
        assertThat(store.deleteRule(exp.id(), UUID.randomUUID())).isEqualTo(404);
        assertThat(store.addRule(UUID.randomUUID(), new ExperimentRuleRequest("RANDOM", 0, 30, null, null, null))).isEqualTo(404);
    }

    @Test
    void overrides_areAddedAndDeleted() {
        ExperimentSettingsDto exp = settings("new-dashboard");
        UUID variant = exp.variants().get(1).id();

        assertThat(store.addOverride(exp.id(),
                new ExperimentOverrideRequest("ATTRIBUTE", "FORCE_VARIANT", 1, null, "beta", "EQUALS", "true", variant), true))
                .isEqualTo(204);

        var overrides = settings("new-dashboard").overrides();
        assertThat(overrides).hasSize(1);
        assertThat(overrides.get(0).targetVariantId()).isEqualTo(variant);
        assertThat(store.deleteOverride(exp.id(), overrides.get(0).id())).isEqualTo(204);
        assertThat(settings("new-dashboard").overrides()).isEmpty();
        assertThat(store.deleteOverride(exp.id(), UUID.randomUUID())).isEqualTo(404);
    }

    @Test
    void anOverrideForcingAForeignVariantIs404() {
        UUID foreign = settings("minecraft.integration").variants().get(0).id();

        assertThat(store.addOverride(idOf("new-dashboard"),
                new ExperimentOverrideRequest("ATTRIBUTE", "FORCE_VARIANT", 1, null, "beta", "EQUALS", "true", foreign), true))
                .isEqualTo(404);
        assertThat(settings("new-dashboard").overrides()).isEmpty();
    }

    @Test
    void aUserOverrideOnAnUnknownLoginIs404() {
        assertThat(store.addOverride(idOf("new-dashboard"),
                new ExperimentOverrideRequest("USER", "EXCLUDE", 1, "ghost", null, null, null, null), false))
                .isEqualTo(404);
        assertThat(store.addOverride(idOf("new-dashboard"),
                new ExperimentOverrideRequest("USER", "EXCLUDE", 1, "known", null, null, null, null), true))
                .isEqualTo(204);
    }
}
