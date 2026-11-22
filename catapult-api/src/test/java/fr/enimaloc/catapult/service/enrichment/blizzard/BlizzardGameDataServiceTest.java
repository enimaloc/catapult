package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlizzardGameDataServiceTest {

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final BlizzardClient client = mock(BlizzardClient.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private BlizzardGameDataService service;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        service = new BlizzardGameDataService(new EnrichmentCache(repository, new EnrichmentProperties(),
                JsonMapper.builder().build()), client);
    }

    @Test
    void tokenPriceInGold() {
        when(client.wowToken(BlizzardRegion.EU)).thenReturn(new WowTokenPrice(470531L, Instant.EPOCH));

        BlizzardPublisher p = service.forGame(BlizzardGame.WOW, BlizzardRegion.EU, Locale.FRENCH,
                FetchBudget.UNLIMITED).orElseThrow();

        assertThat(p.wow().tokenGold()).isEqualTo(470531L);
        assertThat(p.region()).isEqualTo("eu");
        assertThat(p.hearthstone()).isNull();
        assertThat(p.sc2()).isNull();
    }

    @Test
    void latestSetIsFirstWithCards() {
        when(client.hearthstoneSets("fr_FR")).thenReturn(List.of(
                new HearthstoneSet(1, "Empty", "empty", "x", 0), new HearthstoneSet(2, "None", "none", "x", null),
                new HearthstoneSet(3, "Newest", "newest", "x", 145), new HearthstoneSet(4, "Old", "old", "x", 100)));

        BlizzardPublisher p = service.forGame(BlizzardGame.HEARTHSTONE, BlizzardRegion.EU, Locale.FRENCH,
                FetchBudget.UNLIMITED).orElseThrow();

        assertThat(p.hearthstone().latestSet().name()).isEqualTo("Newest");
    }

    @Test
    void d3SeasonAndSc2() {
        when(client.d3CurrentSeason(BlizzardRegion.EU)).thenReturn(34);

        assertThat(service.forGame(BlizzardGame.D3, BlizzardRegion.EU, Locale.FRENCH, FetchBudget.UNLIMITED)
                .orElseThrow().d3().season()).isEqualTo(34);
        assertThat(service.forGame(BlizzardGame.SC2, BlizzardRegion.EU, Locale.FRENCH, FetchBudget.UNLIMITED))
                .isEmpty();
    }

    @Test
    void publicKeysHaveNoMember() {
        when(client.wowToken(BlizzardRegion.EU)).thenReturn(new WowTokenPrice(1L, Instant.EPOCH));
        when(client.hearthstoneSets("fr_FR")).thenReturn(List.of(new HearthstoneSet(3, "N", "n", "x", 1)));

        service.forGame(BlizzardGame.WOW, BlizzardRegion.EU, Locale.FRENCH, FetchBudget.UNLIMITED);
        service.forGame(BlizzardGame.HEARTHSTONE, BlizzardRegion.EU, Locale.FRENCH, FetchBudget.UNLIMITED);

        assertThat(store.values()).extracting(GameEnrichmentCacheEntry::getLookupKey)
                .containsExactlyInAnyOrder("wow-token:eu", "hs-sets:fr_FR");
    }

    @Test
    void publicFetchFailureIsAbsentNotCached() {
        when(client.wowToken(BlizzardRegion.EU)).thenThrow(new BlizzardUnavailableException("down"));

        assertThat(service.wowToken(BlizzardRegion.EU, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.wowToken(BlizzardRegion.EU, FetchBudget.UNLIMITED)).isEmpty();

        verify(client, times(2)).wowToken(BlizzardRegion.EU);
        assertThat(store).isEmpty();
    }

    @Test
    void deckIsCachedByCode() {
        when(client.hearthstoneDeck("AAE", "fr_FR")).thenReturn(new HearthstoneDeck("AAE", "standard", "Jaina",
                "Mage", 30, List.of()));

        assertThat(service.hearthstoneDeck("AAE", Locale.FRENCH, FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.hearthstoneDeck("AAE", Locale.FRENCH, FetchBudget.UNLIMITED)).isPresent();

        verify(client, times(1)).hearthstoneDeck("AAE", "fr_FR");
    }
}
