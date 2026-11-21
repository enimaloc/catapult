package fr.enimaloc.catapult.service.enrichment.hltb;

import com.api.igdb.exceptions.RequestException;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import proto.Game;
import proto.GameTimeToBeat;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HltbServiceTest {

    private static final TimeToBeat SCRAPED = new TimeToBeat(97_163L, 149_918L, 236_144L, 9559,
            TimeToBeat.Origin.HLTB, "https://howlongtobeat.com/game/26286");

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final IgdbClient igdbClient = mock(IgdbClient.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final HltbScraper scraper = mock(HltbScraper.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private HltbService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(cache.get(eq(EnrichmentSource.HLTB), anyString(), eq(TimeToBeat.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<Optional<TimeToBeat>>) inv.getArgument(4)).get());
        when(igdbService.getAppToken()).thenReturn("token");
        service = new HltbService(cache, igdbClient, igdbService, scraper, properties);
    }

    @Test
    void igdbHasData_usesIgdb_noScrape() throws Exception {
        when(igdbClient.findTimeToBeat("1942", "token")).thenReturn(Optional.of(GameTimeToBeat.newBuilder()
                .setHastily(36_000).setNormally(72_000).setCompletely(0).setCount(12).build()));

        Optional<TimeToBeat> result = service.timeToBeat("1942", "The Witcher 3", FetchBudget.UNLIMITED);

        assertThat(result).contains(new TimeToBeat(36_000L, 72_000L, null, 12, TimeToBeat.Origin.IGDB, null));
        verify(cache).get(eq(EnrichmentSource.HLTB), eq("igdb:1942"), eq(TimeToBeat.class), any(), any());
        verify(scraper, never()).search(anyString());
    }

    @Test
    void igdbEmpty_fallsBackToScraper() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token")).thenReturn(Optional.empty());
        when(scraper.search("Hollow Knight")).thenReturn(Optional.of(SCRAPED));

        Optional<TimeToBeat> result = service.timeToBeat("26286", "Hollow Knight", FetchBudget.UNLIMITED);

        assertThat(result).hasValueSatisfying(ttb -> {
            assertThat(ttb.origin()).isEqualTo(TimeToBeat.Origin.HLTB);
            assertThat(ttb.hltbUrl()).isNotNull();
        });
    }

    @Test
    void igdbRowWithoutAnyDuration_fallsBackToScraper() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token"))
                .thenReturn(Optional.of(GameTimeToBeat.newBuilder().setCount(0).build()));
        when(scraper.search("Hollow Knight")).thenReturn(Optional.of(SCRAPED));

        assertThat(service.timeToBeat("26286", "Hollow Knight", FetchBudget.UNLIMITED)).contains(SCRAPED);
    }

    @Test
    void scrapeDisabled_igdbEmpty_empty() throws Exception {
        properties.getHltb().getScrape().setEnabled(false);
        when(igdbClient.findTimeToBeat("26286", "token")).thenReturn(Optional.empty());

        assertThat(service.timeToBeat("26286", "Hollow Knight", FetchBudget.UNLIMITED)).isEmpty();
        verify(scraper, never()).search(anyString());
    }

    @Test
    void noIgdbId_scrapesByName() throws Exception {
        when(scraper.search("Hollow   Knight ")).thenReturn(Optional.of(SCRAPED));

        assertThat(service.timeToBeat(null, "Hollow   Knight ", FetchBudget.UNLIMITED)).contains(SCRAPED);
        verify(cache).get(eq(EnrichmentSource.HLTB), eq("name:hollow knight"), eq(TimeToBeat.class), any(), any());
        verify(igdbClient, never()).findTimeToBeat(anyString(), anyString());
    }

    @Test
    void noIgdbIdNorName_empty() throws Exception {
        assertThat(service.timeToBeat(null, "  ", FetchBudget.UNLIMITED)).isEmpty();
        verify(cache, never()).get(any(), anyString(), any(), any(), any());
    }

    @Test
    void byIgdbId_igdbEmpty_scrapesUnderTheIgdbName() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token")).thenReturn(Optional.empty());
        when(igdbClient.fetchGameById("26286", "name", "token"))
                .thenReturn(List.of(Game.newBuilder().setName("Hollow Knight").build()));
        when(scraper.search("Hollow Knight")).thenReturn(Optional.of(SCRAPED));

        assertThat(service.byIgdbId("26286", FetchBudget.UNLIMITED)).contains(SCRAPED);
        verify(cache).get(eq(EnrichmentSource.HLTB), eq("igdb:26286"), eq(TimeToBeat.class), any(), any());
    }

    @Test
    void noIgdbToken_throws_nothingAsked() throws Exception {
        when(igdbService.getAppToken()).thenReturn("");

        assertThatThrownBy(() -> service.timeToBeat("26286", "Hollow Knight", FetchBudget.UNLIMITED))
                .isInstanceOf(HltbService.IgdbUnavailableException.class);
        verify(igdbClient, never()).findTimeToBeat(anyString(), anyString());
        verify(scraper, never()).search(anyString());
    }

    @Test
    void igdbError_scrapeDisabled_throws_nothingCached() throws Exception {
        properties.getHltb().getScrape().setEnabled(false);
        when(igdbClient.findTimeToBeat("26286", "token")).thenThrow(mock(RequestException.class));
        GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
        HltbService withRealCache = new HltbService(
                new EnrichmentCache(repository, properties, JsonMapper.builder().build()),
                igdbClient, igdbService, scraper, properties);

        assertThat(withRealCache.timeToBeat("26286", "Hollow Knight", FetchBudget.UNLIMITED)).isEmpty();
        verify(repository, never()).save(any());
        assertThatThrownBy(() -> service.timeToBeat("26286", "Hollow Knight", FetchBudget.UNLIMITED))
                .isInstanceOf(HltbService.IgdbUnavailableException.class);
    }

    @Test
    void igdbNameLookupFindsNothing_throws() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token")).thenReturn(Optional.empty());
        when(igdbClient.fetchGameById("26286", "name", "token")).thenReturn(List.of());

        assertThatThrownBy(() -> service.byIgdbId("26286", FetchBudget.UNLIMITED))
                .isInstanceOf(HltbService.IgdbUnavailableException.class);
        verify(scraper, never()).search(anyString());
    }

    @Test
    void scraperUnavailable_propagates() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token")).thenReturn(Optional.empty());
        when(scraper.search("Hollow Knight")).thenThrow(new HltbScraper.UnavailableException("paused"));

        assertThatThrownBy(() -> service.timeToBeat("26286", "Hollow Knight", FetchBudget.UNLIMITED))
                .isInstanceOf(HltbScraper.UnavailableException.class);
        assertThatThrownBy(() -> service.timeToBeat(null, "Hollow Knight", FetchBudget.UNLIMITED))
                .isInstanceOf(HltbScraper.UnavailableException.class);
    }

    @Test
    void originIsSerializedInLowerCase() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();

        String json = mapper.writeValueAsString(SCRAPED);

        assertThat(json).contains("\"origin\":\"hltb\"");
        assertThat(mapper.readValue(json, TimeToBeat.class)).isEqualTo(SCRAPED);
    }
}
