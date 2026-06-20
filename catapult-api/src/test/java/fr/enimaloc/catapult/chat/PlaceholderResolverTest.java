package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderResolverTest {

    private final PlaceholderResolver resolver =
        new PlaceholderResolver(new SimpleMeterRegistry());

    @Test
    void simple_substitution() {
        Optional<String> result = resolver.resolve(
            "Je joue à {game.name}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("Je joue à Halo");
    }

    @Test
    void inline_fallback_used_when_value_blank() {
        Optional<String> result = resolver.resolve(
            "{game.summary|sans description}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("sans description");
    }

    @Test
    void empty_inline_fallback_accepted() {
        Optional<String> result = resolver.resolve(
            "Je joue à {game.name} {game.summary|}",
            contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("Je joue à Halo ");
    }

    @Test
    void inline_takes_precedence_over_db_fallback() {
        Optional<String> result = resolver.resolve(
            "{game.summary|inline}", contextWithName("Halo"),
            Map.of("game.summary", "db"), Locale.FRANCE);
        assertThat(result).contains("inline");
    }

    @Test
    void db_fallback_used_when_no_inline() {
        Optional<String> result = resolver.resolve(
            "{game.summary}", contextWithName("Halo"),
            Map.of("game.summary", "db fallback"), Locale.FRANCE);
        assertThat(result).contains("db fallback");
    }

    @Test
    void required_placeholder_unresolved_returns_empty() {
        Optional<String> result = resolver.resolve(
            "Je joue à {game.name}", GameContext.empty(), Map.of(), Locale.FRANCE);
        assertThat(result).isEmpty();
    }

    @Test
    void blank_result_returns_empty() {
        Optional<String> result = resolver.resolve(
            "{game.summary|}", contextWithName(""), Map.of(), Locale.FRANCE);
        assertThat(result).isEmpty();
    }

    @Test
    void multiple_occurrences_substituted_identically() {
        Optional<String> result = resolver.resolve(
            "{game.name} - {game.name}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("Halo - Halo");
    }

    @Test
    void unknown_path_treated_as_null() {
        Optional<String> result = resolver.resolve(
            "x {game.unknown|fb} y", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("x fb y");
    }

    @Test
    void release_date_formatted_in_locale() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Halo"),
            "100", "Halo", null,
            LocalDate.of(2024, 3, 14),
            Map.of(), null, null, null, null);
        Optional<String> result = resolver.resolve(
            "{game.release_date}", ctx, Map.of(), Locale.FRANCE);
        assertThat(result).contains("14/03/2024");
    }

    @Test
    void resolves_dtddYes() {
        var topics = new fr.enimaloc.catapult.getter.DtddApiClient.DtddTopics(
            java.util.List.of("A dog dies", "Flashing lights"),
            java.util.List.of(), java.util.List.of());
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Stardew"),
            "100", "Stardew", null, null, Map.of(), null, null, topics, null);
        assertThat(resolver.resolve("Triggers: {dtdd.yes}", ctx, Map.of(), Locale.ENGLISH))
            .contains("Triggers: A dog dies, Flashing lights");
    }

    @Test
    void resolves_gameAgerating() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Stardew"),
            "100", "Stardew", null, null, Map.of(), null, null, null, "PEGI 12 — Violence");
        assertThat(resolver.resolve("Rated: {game.agerating}", ctx, Map.of(), Locale.ENGLISH))
            .contains("Rated: PEGI 12 — Violence");
    }

    @Test
    void resolves_dtddYesEmpty_skipsWhenNoFallback() {
        var topics = new fr.enimaloc.catapult.getter.DtddApiClient.DtddTopics(
            java.util.List.of(), java.util.List.of(), java.util.List.of());
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Stardew"),
            "100", "Stardew", null, null, Map.of(), null, null, topics, null);
        assertThat(resolver.resolve("Just {dtdd.yes}", ctx, Map.of(), Locale.ENGLISH))
            .isEmpty();
    }

    @Test
    void no_recursive_expansion_in_fallbacks() {
        Optional<String> result = resolver.resolve(
            "{game.summary|see {game.name}}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("see {game.name}");
    }

    @Test
    void find_unknown_paths_returns_unknown_only() {
        java.util.Set<String> unknown = resolver.findUnknownPaths(
            "Hi {game.name} {game.unknown|x} {game.also_unknown}");
        assertThat(unknown).containsExactlyInAnyOrder("game.unknown", "game.also_unknown");
    }

    private GameContext contextWithName(String name) {
        return new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, name),
            "100", name, null, null, Map.of(), null, null, null, null);
    }
}
