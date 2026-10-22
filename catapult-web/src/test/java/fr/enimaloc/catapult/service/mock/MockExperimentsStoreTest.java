package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
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

    // ---- participants

    private UUID variantId(String experiment, String variant) {
        return settings(experiment).variants().stream().filter(v -> v.key().equals(variant)).findFirst().orElseThrow().id();
    }

    @Test
    void participants_areSeededAndPaged() {
        UUID id = idOf("new-dashboard");

        ExperimentAssignmentsDto first = store.assignments(id, null, null, 0).orElseThrow();
        assertThat(first.page().content()).hasSize(20);
        assertThat(first.page().totalElements()).isEqualTo(45);
        assertThat(first.page().totalPages()).isEqualTo(3);
        assertThat(store.assignments(id, null, null, 2).orElseThrow().page().content()).hasSize(5);
        assertThat(store.assignments(id, null, null, 3).orElseThrow().page().content()).isEmpty();
        assertThat(first.counts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(45);
        assertThat(store.assignments(idOf("minecraft.integration"), null, null, 0).orElseThrow().page().totalElements())
                .isBetween(1L, 10L);
    }

    @Test
    void participants_filterByVariant_andSearchByLogin_withCountsKeptWhole() {
        UUID id = idOf("new-dashboard");
        UUID control = variantId("new-dashboard", "control");

        ExperimentAssignmentsDto onlyControl = store.assignments(id, control, null, 0).orElseThrow();
        assertThat(onlyControl.page().content()).isNotEmpty().allMatch(a -> a.variantId().equals(control));
        assertThat(onlyControl.page().totalElements()).isEqualTo(onlyControl.counts().get(control));
        assertThat(onlyControl.counts()).hasSize(2);

        String login = store.assignments(id, null, null, 0).orElseThrow().page().content().get(0).twitchUsername();
        ExperimentAssignmentsDto found = store.assignments(id, null, "  " + login.toUpperCase() + " ", 0).orElseThrow();
        assertThat(found.page().content()).extracting(ExperimentAssignmentDto::twitchUsername).contains(login);
        assertThat(store.assignments(id, null, "no-such-login", 0).orElseThrow().page().content()).isEmpty();
    }

    @Test
    void participants_ofAnUnknownExperimentOrForeignVariant_areNotFound() {
        assertThat(store.assignments(UUID.randomUUID(), null, null, 0)).isEmpty();
        assertThat(store.assignments(idOf("new-dashboard"), variantId("minecraft.integration", "enabled"), null, 0)).isEmpty();
    }

    @Test
    void move_updatesTheAssignment_andForcesTheVariantWithAUserOverride() {
        UUID id = idOf("new-dashboard");
        UUID control = variantId("new-dashboard", "control");
        UUID other = variantId("new-dashboard", "new");
        ExperimentAssignmentDto who = store.assignments(id, control, null, 0).orElseThrow().page().content().get(0);
        assertThat(who.forced()).isFalse();

        assertThat(store.move(id, who.userId(), other)).isEqualTo(204);

        ExperimentAssignmentDto moved = store.assignments(id, other, who.twitchUsername(), 0).orElseThrow()
                .page().content().stream().filter(a -> a.userId().equals(who.userId())).findFirst().orElseThrow();
        assertThat(moved.forced()).isTrue();
        assertThat(settings("new-dashboard").overrides()).hasSize(1);
        var override = settings("new-dashboard").overrides().get(0);
        assertThat(override.type()).isEqualTo("USER");
        assertThat(override.action()).isEqualTo("FORCE_VARIANT");
        assertThat(override.twitchUsername()).isEqualTo(who.twitchUsername());
        assertThat(override.targetVariantId()).isEqualTo(other);

        // moving again replaces the override rather than stacking another
        assertThat(store.move(id, who.userId(), control)).isEqualTo(204);
        assertThat(settings("new-dashboard").overrides()).hasSize(1);
        assertThat(settings("new-dashboard").overrides().get(0).targetVariantId()).isEqualTo(control);
    }

    @Test
    void move_refusals_followTheApi() {
        UUID id = idOf("new-dashboard");
        UUID other = variantId("new-dashboard", "new");
        UUID user = store.assignments(id, null, null, 0).orElseThrow().page().content().get(0).userId();

        assertThat(store.move(UUID.randomUUID(), user, other)).isEqualTo(404);
        assertThat(store.move(id, user, null)).isEqualTo(400);
        assertThat(store.move(id, UUID.randomUUID(), other)).isEqualTo(404);
        assertThat(store.move(id, user, variantId("minecraft.integration", "enabled"))).isEqualTo(404);
        assertThat(store.action(id, "end")).isEqualTo(204);
        assertThat(store.move(id, user, other)).isEqualTo(409);
    }
}
