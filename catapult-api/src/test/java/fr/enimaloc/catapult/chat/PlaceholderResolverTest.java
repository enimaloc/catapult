package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
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
            "Je joue à {game#name}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("Je joue à Halo");
    }

    @Test
    void inline_fallback_used_when_value_blank() {
        Optional<String> result = resolver.resolve(
            "{game#summary|sans description}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("sans description");
    }

    @Test
    void empty_inline_fallback_accepted() {
        Optional<String> result = resolver.resolve(
            "Je joue à {game#name} {game#summary|}",
            contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("Je joue à Halo ");
    }

    @Test
    void inline_takes_precedence_over_db_fallback() {
        Optional<String> result = resolver.resolve(
            "{game#summary|inline}", contextWithName("Halo"),
            Map.of("game#summary", "db"), Locale.FRANCE);
        assertThat(result).contains("inline");
    }

    @Test
    void db_fallback_used_when_no_inline() {
        Optional<String> result = resolver.resolve(
            "{game#summary}", contextWithName("Halo"),
            Map.of("game#summary", "db fallback"), Locale.FRANCE);
        assertThat(result).contains("db fallback");
    }

    @Test
    void required_placeholder_unresolved_returns_empty() {
        Optional<String> result = resolver.resolve(
            "Je joue à {game#name}", GameContext.empty(), Map.of(), Locale.FRANCE);
        assertThat(result).isEmpty();
    }

    @Test
    void blank_result_returns_empty() {
        Optional<String> result = resolver.resolve(
            "{game#summary|}", contextWithName(""), Map.of(), Locale.FRANCE);
        assertThat(result).isEmpty();
    }

    @Test
    void multiple_occurrences_substituted_identically() {
        Optional<String> result = resolver.resolve(
            "{game#name} - {game#name}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("Halo - Halo");
    }

    @Test
    void unknown_path_treated_as_null() {
        Optional<String> result = resolver.resolve(
            "x {game#unknown|fb} y", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("x fb y");
    }

    @Test
    void release_date_formatted_in_locale() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Halo"),
            "100", "Halo", null,
            LocalDate.of(2024, 3, 14),
            Map.of(), null, null, Set.of(), Map.of(), null,
            null, null, List.of(), List.of(), List.of());
        Optional<String> result = resolver.resolve(
            "{game#release_date}", ctx, Map.of(), Locale.FRANCE);
        assertThat(result).contains("14/03/2024");
    }

    @Test
    void resolves_gameAgerating() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Stardew"),
            "100", "Stardew", null, null, Map.of(), null, null,
            Set.of(), Map.of(), "PEGI 12 — Violence",
            null, null, List.of(), List.of(), List.of());
        assertThat(resolver.resolve("Rated: {game#agerating}", ctx, Map.of(), Locale.ENGLISH))
            .contains("Rated: PEGI 12 — Violence");
    }

    @Test
    void resolves_gameRating_roundedToNearestInteger() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Valorant"),
            "100", "Valorant", null, null, Map.of(), null, null,
            Set.of(), Map.of(), null,
            78.3421, null, List.of(), List.of(), List.of());
        assertThat(resolver.resolve("Rating: {game#rating}", ctx, Map.of(), Locale.ENGLISH))
            .contains("Rating: 78");
    }

    @Test
    void resolves_gameRating_emptyWhenUnset() {
        Optional<String> result = resolver.resolve(
            "x {game#rating|fb} y", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("x fb y");
    }

    @Test
    void resolves_gameCriticRating_roundedToNearestInteger() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Valorant"),
            "100", "Valorant", null, null, Map.of(), null, null,
            Set.of(), Map.of(), null,
            null, 85.9, List.of(), List.of(), List.of());
        assertThat(resolver.resolve("Critic: {game#critic_rating}", ctx, Map.of(), Locale.ENGLISH))
            .contains("Critic: 86");
    }

    @Test
    void resolves_gamePlatforms_commaJoined() {
        GameContext ctx = new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Valorant"),
            "100", "Valorant", null, null, Map.of(), null, null,
            Set.of(), Map.of(), null,
            null, null, List.of("PC", "PlayStation 5", "Xbox Series X"), List.of(), List.of());
        assertThat(resolver.resolve("{game#platforms}", ctx, Map.of(), Locale.ENGLISH))
            .contains("PC, PlayStation 5, Xbox Series X");
    }

    @Test
    void resolves_gamePlatforms_emptyWhenNoPlatforms() {
        Optional<String> result = resolver.resolve(
            "x {game#platforms|fb} y", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("x fb y");
    }

    @Test
    void resolvesTwActive_joinsLabels() {
        GameContext ctx = ctxWithTws(
            Set.of("violence_graphic", "death_of_animal"),
            Map.of("violence_graphic", "Violence (graphic)",
                "death_of_animal", "Death of animal"));
        assertThat(resolver.resolve("{tw#active}", ctx, Map.of(), Locale.ENGLISH))
            .hasValueSatisfying(s -> assertThat(s)
                .contains("Violence (graphic)")
                .contains("Death of animal"));
    }

    @Test
    void resolvesTwActive_isEmptyWhenNoneActive_skipsWhenNoFallback() {
        GameContext ctx = ctxWithTws(Set.of(), Map.of());
        assertThat(resolver.resolve("Just {tw#active}", ctx, Map.of(), Locale.ENGLISH))
            .isEmpty();
    }

    @Test
    void resolvesTwSpecific_returnsLabelIfActive() {
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("violence_graphic"));
        GameContext ctx = ctxWithTws(Set.of("violence_graphic"),
            Map.of("violence_graphic", "Violence"));
        assertThat(resolver.resolve("{tw#violence_graphic}", ctx, Map.of(), Locale.ENGLISH))
            .hasValue("Violence");
    }

    @Test
    void resolvesTwSpecific_returnsEmptyIfInactive() {
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("flashing_lights"));
        GameContext ctx = ctxWithTws(Set.of("violence_graphic"),
            Map.of("violence_graphic", "Violence"));
        assertThat(resolver.resolve("{tw#flashing_lights|none}", ctx, Map.of(), Locale.ENGLISH))
            .hasValue("none");
    }

    @Test
    void inline_fallback_is_recursively_expanded() {
        Optional<String> result = resolver.resolve(
            "{game#summary|see {game#name}}", contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("see Halo");
    }

    @Test
    void nested_placeholder_in_fallback_resolves_inner_value() {
        // The shipped !triggers preset uses {tw#active|{game#agerating|none}}
        // exactly to surface the age rating when no TW signals applied.
        GameContext ctx = new GameContext(
            new DetectedGame("g1", GameBinding.SourceType.STEAM, "Halo"),
            "igdb-1", "Halo", null, null, Map.of(), null, null,
            Set.of(), Map.of(), "PEGI 12",
            null, null, List.of(), List.of(), List.of());
        Optional<String> result = resolver.resolve(
            "{tw#active|{game#agerating|aucune information disponible}}",
            ctx, Map.of(), Locale.FRANCE);
        assertThat(result).contains("PEGI 12");
    }

    @Test
    void nested_placeholder_falls_through_to_inner_fallback() {
        Optional<String> result = resolver.resolve(
            "{tw#active|{game#agerating|aucune information disponible}}",
            contextWithName("Halo"), Map.of(), Locale.FRANCE);
        assertThat(result).contains("aucune information disponible");
    }

    @Test
    void find_unknown_paths_returns_unknown_only() {
        Set<String> unknown = resolver.findUnknownPaths(
            "Hi {game#name} {game#unknown|x} {game#also_unknown}");
        assertThat(unknown).containsExactlyInAnyOrder("game#unknown", "game#also_unknown");
    }

    @Test
    void findUnknownPaths_doesNotFlagBareElseAsAnUnknownPlaceholder() {
        // {else} is 4 lowercase letters with no '#' — indistinguishable from a legit simple
        // placeholder by character shape alone, unlike {if ...}/{for ...} whose condition/header
        // always contains a space plus non-path characters. Without excluding structural
        // keywords, this rejected the save of ANY {if}...{else}...{/if} command with a 400
        // "Unknown placeholders: else".
        Set<String> unknown = resolver.findUnknownPaths(
            "{if game#name == \"Halo\"}a{else}b{/if}");
        assertThat(unknown).isEmpty();
    }

    @Test
    void findUnknownPaths_doesNotFlagBareElseInsideNestedServiceCallExpressions() {
        Set<String> unknown = resolver.findUnknownPaths(
            "{if catapult#getGame().sourceType == \"STEAM\"}"
            + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.lang).short_description}"
            + "{else}{igdb#getGame(catapult#getGame().sourceName)}{/if}");
        assertThat(unknown).isEmpty();
    }

    @Test
    void findUnknownPaths_doesNotFlagABareVarRefToAVarDeclaredEarlierInTheTemplate() {
        // {var game = arg(0, "")} declares "game" as a VarRefExpr, per
        // CommandDslParser#bareTagWithoutHashIsAVarRefNotAContextGet — a later bare {game} tag
        // reads that variable, not a "game" context path (which doesn't exist; only "game#name"
        // etc. do). Reproduced from a save that failed with "Unknown placeholders: game" for
        // {var game = arg(0, "")}{if igdb#getGame(game).id != ""}{catapult#setGame(game,
        // igdb#getGame(game).id)}Jeu mis à jour: {game}{else}{game} non trouvé{/if}.
        Set<String> unknown = resolver.findUnknownPaths(
            "{var game = arg(0, \"\")}"
            + "{if igdb#getGame(game).id != \"\"}"
            + "{catapult#setGame(game, igdb#getGame(game).id)}Jeu mis à jour: {game}"
            + "{else}{game} non trouvé{/if}");
        assertThat(unknown).isEmpty();
    }

    @Test
    void findUnknownPaths_recognizesAForEachBindingNameAsKnownInsideTheLoopBody() {
        Set<String> unknown = resolver.findUnknownPaths(
            "{for f in fallbacks}{f} {/for}");
        assertThat(unknown).isEmpty();
    }

    @Test
    void findUnknownPaths_stillFlagsABareTagThatMatchesNoDeclaredVarOrKnownPath() {
        Set<String> unknown = resolver.findUnknownPaths(
            "{var game = arg(0, \"\")}{totallyunrelated}");
        assertThat(unknown).containsExactly("totallyunrelated");
    }

    @Test
    void findUnknownPaths_acceptsTwActiveAndKnownSlugs_rejectsUnknown() {
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("violence_graphic"));
        Set<String> unknown = resolver.findUnknownPaths(
            "{tw#active} {tw#violence_graphic} {tw#nonexistent}");
        assertThat(unknown).containsExactly("tw#nonexistent");
    }

    private GameContext contextWithName(String name) {
        return new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, name),
            "100", name, null, null, Map.of(), null, null,
            Set.of(), Map.of(), null,
            null, null, List.of(), List.of(), List.of());
    }

    private GameContext ctxWithTws(Set<String> tws, Map<String, String> labels) {
        return new GameContext(
            new DetectedGame("1", GameBinding.SourceType.STEAM, "Stardew"),
            "100", "Stardew", null, null, Map.of(), null, null,
            tws, labels, null,
            null, null, List.of(), List.of(), List.of());
    }
}
