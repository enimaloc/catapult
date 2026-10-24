package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockGameMappingsStoreTest {
    private final Instant now = Instant.parse("2026-10-24T10:00:00Z");
    private final MockGameMappingsStore store = new MockGameMappingsStore(Clock.fixed(now, ZoneOffset.UTC), 2);

    private static GameMappingRequest request(String type, String id, String category) {
        return new GameMappingRequest(type, id, category, category == null ? null : "Name " + category, null, null, false);
    }

    @Test
    void seeded_hasTwoMappings_newestFirst() {
        assertThat(store.list(0).content()).hasSize(2);
        assertThat(store.list(0).totalElements()).isEqualTo(2);
        assertThat(store.list(0).content()).extracting(GameMappingDto::updatedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void missing_isPaged_overTwoPages() {
        assertThat(store.missing(0).content()).hasSize(2);
        assertThat(store.missing(0).totalPages()).isEqualTo(2);
        assertThat(store.missing(0).totalElements()).isEqualTo(3);
        assertThat(store.missing(1).content()).hasSize(1);
        assertThat(store.missing(1).last()).isTrue();
        assertThat(store.missing(5).content()).isEmpty();
    }

    @Test
    void create_answers201_stampsTheMapping_andDropsTheGameFromMissing() {
        var missing = store.missing(0).content().getFirst();

        ApiClient.Result<GameMappingResult> result = store.create(
                request(missing.sourceType(), missing.sourceId(), "509658"), "alice");

        assertThat(result.status()).isEqualTo(201);
        GameMappingDto mapping = result.body().mapping();
        assertThat(mapping.id()).isNotNull();
        assertThat(mapping.updatedAt()).isEqualTo(now);
        assertThat(mapping.updatedBy()).isEqualTo("alice");
        assertThat(mapping.sourceName()).isEqualTo(missing.sourceName());
        assertThat(store.list(0).content().getFirst().id()).isEqualTo(mapping.id());
        assertThat(store.missing(0).totalElements()).isEqualTo(2);
    }

    @Test
    void create_refusesADuplicate_with409() {
        var existing = store.list(0).content().getFirst();

        assertThat(store.create(request(existing.sourceType(), existing.sourceId(), "1"), "a").status()).isEqualTo(409);
    }

    @Test
    void create_trimsTheId_soAPaddedDuplicateIs409() {
        assertThat(store.create(request("STEAM", " 730 ", "1"), "a").status()).isEqualTo(409);
        assertThat(store.create(request("STEAM", " 99 ", "1"), "a").body().mapping().sourceId()).isEqualTo("99");
    }

    @Test
    void create_refusesManualAMissingIdOrAMissingCategory_with400() {
        assertThat(store.create(request("MANUAL", "1", "1"), "a").status()).isEqualTo(400);
        assertThat(store.create(request("STEAM", " ", "1"), "a").status()).isEqualTo(400);
        assertThat(store.create(request("STEAM", "999", null), "a").status()).isEqualTo(400);
        assertThat(store.create(request(null, "999", "1"), "a").status()).isEqualTo(400);
    }

    @Test
    void update_changesTheCategory_andAnUnknownIdIs404() {
        var existing = store.list(0).content().getFirst();

        var updated = store.update(existing.id(), new GameMappingRequest(existing.sourceType(), existing.sourceId(),
                "42", "Answer", Set.of("violence"), Set.of(), true), "bob");

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().mapping().twitchGameId()).isEqualTo("42");
        assertThat(updated.body().mapping().ccls()).containsExactly("violence");
        assertThat(updated.body().mapping().updatedBy()).isEqualTo("bob");
        assertThat(store.update(UUID.randomUUID(), request("STEAM", "1", "1"), "bob").status()).isEqualTo(404);
    }

    @Test
    void delete_removesTheMapping_andAnUnknownIdIs404() {
        var existing = store.list(0).content().getFirst();

        var deleted = store.delete(existing.id(), false);

        assertThat(deleted.status()).isEqualTo(200);
        assertThat(deleted.body().mapping()).isNull();
        assertThat(store.list(0).totalElements()).isEqualTo(1);
        assertThat(store.delete(existing.id(), false).status()).isEqualTo(404);
    }

    @Test
    void detected_filtersByPlatformAndNameOrId() {
        assertThat(store.detected("STEAM", "")).isNotEmpty().allMatch(d -> d.sourceType().equals("STEAM"));
        var first = store.detected("STEAM", "").getFirst();
        assertThat(store.detected("STEAM", first.sourceName().substring(0, 3).toUpperCase())).contains(first);
        assertThat(store.detected("STEAM", "zzzz-none")).isEmpty();
        assertThat(store.detected(null, "")).isEmpty();
    }
}
