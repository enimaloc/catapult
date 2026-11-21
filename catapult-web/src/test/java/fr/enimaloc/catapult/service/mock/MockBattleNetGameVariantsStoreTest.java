package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantDto;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockBattleNetGameVariantsStoreTest {
    private static final UUID FOREVER = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private final MockBattleNetGameVariantsStore store = new MockBattleNetGameVariantsStore();

    private static BattleNetGameVariantRequest request(String key, List<String> patterns) {
        return new BattleNetGameVariantRequest("WoW", key, "PREFIX", patterns, " WoW " + key + " ", true);
    }

    @Test
    void seeded_withWowForeverDisabled() {
        assertThat(store.list()).singleElement().satisfies(v -> {
            assertThat(v.sourceId()).isEqualTo("WoW:Forever");
            assertThat(v.patterns()).containsExactly("Forever - ");
            assertThat(v.enabled()).isFalse();
        });
    }

    @Test
    void create_keepsThePatternsAsTyped_andSortsByGameThenKey() {
        var created = store.create(request("Classic", List.of("Classic - ")), "admin");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().sourceId()).isEqualTo("WoW:Classic");
        assertThat(created.body().patterns()).containsExactly("Classic - ");
        assertThat(created.body().displayName()).isEqualTo("WoW Classic");
        assertThat(store.list()).extracting(BattleNetGameVariantDto::variantKey).containsExactly("Classic", "Forever");
    }

    @Test
    void create_refusesInvalidOrTakenKeys() {
        assertThat(store.create(request("Forever", List.of("x")), "admin").status()).isEqualTo(409);
        assertThat(store.create(request("For ever", List.of("x")), "admin").status()).isEqualTo(400);
        assertThat(store.create(request("Classic", List.of(" ")), "admin").status()).isEqualTo(400);
        assertThat(store.create(request("Classic", List.of()), "admin").status()).isEqualTo(400);
        assertThat(store.create(new BattleNetGameVariantRequest("WoW", "Classic", "REGEX", List.of("x"), "n", true),
                "admin").status()).isEqualTo(400);
        assertThat(store.create(null, "admin").status()).isEqualTo(400);
    }

    @Test
    void update_changesAVariant_butNeverItsGameOrKey() {
        var changed = store.update(FOREVER, request("Forever", List.of("Forever - ", "Für immer - ")), "admin");

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body().enabled()).isTrue();
        assertThat(changed.body().updatedBy()).isEqualTo("admin");
        assertThat(store.list()).singleElement().satisfies(v -> assertThat(v.patterns()).hasSize(2));
        assertThat(store.update(FOREVER, request("Classic", List.of("x")), "admin").status()).isEqualTo(400);
        assertThat(store.update(UUID.randomUUID(), request("Forever", List.of("x")), "admin").status()).isEqualTo(404);
    }

    @Test
    void delete_removesAVariant_andAnUnknownOneIs404() {
        assertThat(store.delete(FOREVER).status()).isEqualTo(204);
        assertThat(store.list()).isEmpty();
        assertThat(store.delete(FOREVER).status()).isEqualTo(404);
    }
}
