package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant;
import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant.MatchType;
import fr.enimaloc.catapult.repository.battlenet.BattleNetGameVariantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BattleNetGameVariantsTest {

    private static final String FOREVER_TEXT = "Forever - Normal - Undercity";

    private static BattleNetGameVariant variant(String key, MatchType type, Instant createdAt, String... patterns) {
        BattleNetGameVariant variant = new BattleNetGameVariant();
        variant.setFourcc("WoW");
        variant.setVariantKey(key);
        variant.setMatchType(type);
        variant.setPatterns(List.of(patterns));
        variant.setDisplayName("World of Warcraft: " + key);
        variant.setEnabled(true);
        variant.setCreatedAt(createdAt);
        return variant;
    }

    private static BattleNetGameVariant forever() {
        return variant("Forever", MatchType.PREFIX, Instant.EPOCH, "Forever - ");
    }

    @Test
    void prefixMatchesIgnoringCase() {
        assertThat(BattleNetGameVariants.match(List.of(forever()), "WoW", "forever - Normal - Undercity"))
                .map(BattleNetGameVariant::sourceId).contains("WoW:Forever");
    }

    @Test
    void prefixDoesNotMatchElsewhere() {
        assertThat(BattleNetGameVariants.match(List.of(forever()), "WoW", "The Waking Shores - Khaz Modan")).isEmpty();
        assertThat(BattleNetGameVariants.match(List.of(forever()), "WoW", "x Forever - y")).isEmpty();
    }

    @Test
    void containsMatchesAnywhere() {
        BattleNetGameVariant hardcore = variant("Hardcore", MatchType.CONTAINS, Instant.EPOCH, "Hardcore");

        assertThat(BattleNetGameVariants.match(List.of(hardcore), "WoW", "Classic - Hardcore - X")).contains(hardcore);
    }

    @Test
    void otherFourccNeverMatches() {
        assertThat(BattleNetGameVariants.match(List.of(forever()), "WTCG", FOREVER_TEXT)).isEmpty();
    }

    @Test
    void nullOrBlankActivityNeverMatches() {
        BattleNetGameVariant anything = variant("Any", MatchType.CONTAINS, Instant.EPOCH, " ");

        assertThat(BattleNetGameVariants.match(List.of(anything), "WoW", null)).isEmpty();
        assertThat(BattleNetGameVariants.match(List.of(anything), "WoW", "  ")).isEmpty();
    }

    @Test
    void anyPatternOfAVariantMatches() {
        BattleNetGameVariant forever = variant("Forever", MatchType.PREFIX, Instant.EPOCH, "Forever - ", "Für immer - ");

        assertThat(BattleNetGameVariants.match(List.of(forever), "WoW", "Für immer - Normal")).contains(forever);
    }

    @Test
    void longestMatchingPatternWins() {
        BattleNetGameVariant broad = variant("Broad", MatchType.PREFIX, Instant.EPOCH, "Forever");
        BattleNetGameVariant hardcore = variant("Hardcore", MatchType.PREFIX, Instant.EPOCH.plusSeconds(1),
                "Forever - Hardcore");

        assertThat(BattleNetGameVariants.match(List.of(broad, hardcore), "WoW", "Forever - Hardcore - X"))
                .contains(hardcore);
    }

    @Test
    void equalLengthsGoToTheOldestVariant() {
        BattleNetGameVariant newer = variant("Newer", MatchType.CONTAINS, Instant.EPOCH.plusSeconds(1), "Normal");
        BattleNetGameVariant older = variant("Older", MatchType.CONTAINS, Instant.EPOCH, "Forever");

        assertThat(BattleNetGameVariants.match(List.of(newer, older), "WoW", FOREVER_TEXT)).contains(older);
    }

    @Test
    void resolveUsesOnlyEnabledRules() {
        BattleNetGameVariantRepository repository = mock(BattleNetGameVariantRepository.class);
        when(repository.findByEnabledTrue()).thenReturn(List.of(forever()));

        assertThat(new BattleNetGameVariants(repository).resolve("WoW", FOREVER_TEXT))
                .map(BattleNetGameVariant::sourceId).contains("WoW:Forever");
        verify(repository).findByEnabledTrue();
    }

    @Test
    void resolveFallsBackToEmptyWhenTheLoadFails() {
        BattleNetGameVariantRepository repository = mock(BattleNetGameVariantRepository.class);
        when(repository.findByEnabledTrue()).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(new BattleNetGameVariants(repository).resolve("WoW", FOREVER_TEXT)).isEmpty();
    }

    @Test
    void rulesAreCachedUntilInvalidated() {
        BattleNetGameVariantRepository repository = mock(BattleNetGameVariantRepository.class);
        when(repository.findByEnabledTrue()).thenReturn(List.of(forever()), List.of());
        BattleNetGameVariants variants = new BattleNetGameVariants(repository);

        assertThat(variants.resolve("WoW", FOREVER_TEXT)).isPresent();
        assertThat(variants.resolve("WoW", FOREVER_TEXT)).isPresent();
        variants.invalidate();

        assertThat(variants.resolve("WoW", FOREVER_TEXT)).isEmpty();
        verify(repository, times(2)).findByEnabledTrue();
    }
}
