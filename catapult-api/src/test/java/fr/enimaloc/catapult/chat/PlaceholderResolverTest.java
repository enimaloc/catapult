package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaceholderResolverTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private TwPlaceholderRegistry twRegistry;
    private PlaceholderResolver resolver;

    @BeforeEach
    void setUp() {
        twRegistry = mock(TwPlaceholderRegistry.class);
        when(twRegistry.getKnownPaths()).thenReturn(Set.of());
        resolver = new PlaceholderResolver(meterRegistry, twRegistry);
    }

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
            Map.of(), null, null, Set.of(), Map.of(), null);
        Optional<String> result = resolver.resolve(
            "{game.release_date}", ctx, Map.of(), Locale.FRANCE);
        assertThat(result).contains("14/03/2024");
    }

    @Test
    void resolves_gameAgerating() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Stardew"),
            "100", "Stardew", null, null, Map.of(), null, null,
            Set.of(), Map.of(), "PEGI 12 — Violence");
        assertThat(resolver.resolve("Rated: {game.agerating}", ctx, Map.of(), Locale.ENGLISH))
            .contains("Rated: PEGI 12 — Violence");
    }

    @Test
    void resolvesTwActive_joinsLabels() {
        GameContext ctx = ctxWithTws(
            Set.of("violence_graphic", "death_of_animal"),
            Map.of("violence_graphic", "Violence (graphic)",
                "death_of_animal", "Death of animal"));
        assertThat(resolver.resolve("{tw.active}", ctx, Map.of(), Locale.ENGLISH))
            .hasValueSatisfying(s -> assertThat(s)
                .contains("Violence (graphic)")
                .contains("Death of animal"));
    }

    @Test
    void resolvesTwActive_isEmptyWhenNoneActive_skipsWhenNoFallback() {
        GameContext ctx = ctxWithTws(Set.of(), Map.of());
        assertThat(resolver.resolve("Just {tw.active}", ctx, Map.of(), Locale.ENGLISH))
            .isEmpty();
    }

    @Test
    void resolvesTwSpecific_returnsLabelIfActive() {
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("violence_graphic"));
        GameContext ctx = ctxWithTws(Set.of("violence_graphic"),
            Map.of("violence_graphic", "Violence"));
        assertThat(resolver.resolve("{tw.violence_graphic}", ctx, Map.of(), Locale.ENGLISH))
            .hasValue("Violence");
    }

    @Test
    void resolvesTwSpecific_returnsEmptyIfInactive() {
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("flashing_lights"));
        GameContext ctx = ctxWithTws(Set.of("violence_graphic"),
            Map.of("violence_graphic", "Violence"));
        assertThat(resolver.resolve("{tw.flashing_lights|none}", ctx, Map.of(), Locale.ENGLISH))
            .hasValue("none");
    }

    @Test
    void inline_fallback_is_recursively_expanded() {
        Optional<String> result = resolver.resolve(
            "{game.summary|see {game.name}}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("see Halo");
    }

    @Test
    void nested_placeholder_in_fallback_resolves_inner_value() {
        // The shipped !triggers preset uses {tw.active|{game.agerating|none}}
        // exactly to surface the age rating when no TW signals applied.
        GameContext ctx = new GameContext(
            new DetectedGame("g1", GameBinding.SourceType.STEAM, "Halo"),
            "igdb-1", "Halo", null, null, Map.of(), null, null,
            Set.of(), Map.of(), "PEGI 12");
        Optional<String> result = resolver.resolve(
            "{tw.active|{game.agerating|aucune information disponible}}",
            ctx, Map.of(), Locale.FRANCE);
        assertThat(result).contains("PEGI 12");
    }

    @Test
    void nested_placeholder_falls_through_to_inner_fallback() {
        Optional<String> result = resolver.resolve(
            "{tw.active|{game.agerating|aucune information disponible}}",
            contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("aucune information disponible");
    }

    @Test
    void find_unknown_paths_returns_unknown_only() {
        Set<String> unknown = resolver.findUnknownPaths(
            "Hi {game.name} {game.unknown|x} {game.also_unknown}");
        assertThat(unknown).containsExactlyInAnyOrder("game.unknown", "game.also_unknown");
    }

    @Test
    void findUnknownPaths_acceptsTwActiveAndKnownSlugs_rejectsUnknown() {
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("violence_graphic"));
        Set<String> unknown = resolver.findUnknownPaths(
            "{tw.active} {tw.violence_graphic} {tw.nonexistent}");
        assertThat(unknown).containsExactly("tw.nonexistent");
    }

    private GameContext contextWithName(String name) {
        return new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, name),
            "100", name, null, null, Map.of(), null, null,
            Set.of(), Map.of(), null);
    }

    private GameContext ctxWithTws(Set<String> tws, Map<String, String> labels) {
        return new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Stardew"),
            "100", "Stardew", null, null, Map.of(), null, null,
            tws, labels, null);
    }
}
