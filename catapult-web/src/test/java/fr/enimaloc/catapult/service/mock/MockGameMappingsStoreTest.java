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
    void seeded_hasTheBlizzardTitles_newestFirst_oneIgnoredByDefault() {
        assertThat(store.list(0).content()).hasSize(2);
        assertThat(store.list(0).totalElements()).isEqualTo(12);
        assertThat(store.list(0).content()).extracting(GameMappingDto::updatedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        var all = new java.util.ArrayList<GameMappingDto>();
        for (int page = 0; page < 6; page++) all.addAll(store.list(page).content());
        // A wallpaper app ignored without a category
        assertThat(all).filteredOn(GameMappingDto::ignoredByDefault).singleElement()
                .satisfies(m -> assertThat(m.twitchGameId()).isNull());
        // The nine Blizzard titles, each pinned to its IGDB game (the IGDB name differs from the title's for two)
        assertThat(all).filteredOn(m -> m.sourceType().equals("BATTLENET")).hasSize(9)
                .allSatisfy(m -> assertThat(m.twitchGameId()).isNotNull())
                .extracting(GameMappingDto::sourceId, GameMappingDto::igdbId, GameMappingDto::igdbGameName)
                .contains(org.assertj.core.groups.Tuple.tuple("WoW", "123", "World of Warcraft"),
                        org.assertj.core.groups.Tuple.tuple("S2", "239", "StarCraft II: Wings of Liberty"),
                        org.assertj.core.groups.Tuple.tuple("W3", "111650", "Warcraft III: Reforged"));
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
        assertThat(store.list(0).totalElements()).isEqualTo(11);
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

    @Test
    void igdbGames_searchesTheFixedListByName_andIsEmptyWithoutAQuery() {
        assertThat(store.igdbGames("warcraft")).extracting(o -> o.name())
                .contains("World of Warcraft", "Warcraft III: Reforged");
        assertThat(store.igdbGames("WORLD OF").getFirst().releaseYear()).isEqualTo(2004);
        assertThat(store.igdbGames("zzzz-none")).isEmpty();
        assertThat(store.igdbGames("  ")).isEmpty();
        assertThat(store.igdbGames(null)).isEmpty();
    }

    @Test
    void create_withAnIgdbGame_deducesTheCategory_orRefusesWhenThereIsNone() {
        var deduced = store.create(new GameMappingRequest("STEAM", "413150", null, null, null, null, false, false, "125165"), "me");

        assertThat(deduced.status()).isEqualTo(201);
        assertThat(deduced.body().mapping()).satisfies(m -> {
            assertThat(m.igdbId()).isEqualTo("125165");
            assertThat(m.igdbGameName()).isEqualTo("Diablo IV");
            assertThat(m.twitchGameId()).isEqualTo("515024");
        });
        // An explicit category wins over the deduced one
        var explicit = store.create(new GameMappingRequest("STEAM", "99", "1", "One", null, null, false, false, "125165"), "me");
        assertThat(explicit.body().mapping().twitchGameId()).isEqualTo("1");
        // An IGDB game with no Twitch category, or an unknown one: nothing to deduce
        assertThat(store.create(new GameMappingRequest("STEAM", "98", null, null, null, null, false, false, "9999"), "me")
                .status()).isEqualTo(400);
        assertThat(store.create(new GameMappingRequest("STEAM", "97", null, null, null, null, false, false, "nope"), "me")
                .status()).isEqualTo(400);
    }

    @Test
    void create_withoutCategory_onlyWhenIgnoredByDefault() {
        assertThat(store.create(new GameMappingRequest("STEAM", "413150", null, null, null, null, false), "me")
                .status()).isEqualTo(400);

        var created = store.create(new GameMappingRequest("STEAM", "413150", null, null, null, null, false, true, null), "me");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().mapping().ignoredByDefault()).isTrue();
    }
}
