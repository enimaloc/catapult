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
import proto.GameTimeToBeat;
import tools.jackson.databind.json.JsonMapper;

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

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final IgdbClient igdbClient = mock(IgdbClient.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private HltbService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(cache.get(eq(EnrichmentSource.HLTB), anyString(), eq(TimeToBeat.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<Optional<TimeToBeat>>) inv.getArgument(4)).get());
        when(igdbService.getAppToken()).thenReturn("token");
        service = new HltbService(cache, igdbClient, igdbService);
    }

    @Test
    void igdbHasData_usesIgdb() throws Exception {
        when(igdbClient.findTimeToBeat("1942", "token")).thenReturn(Optional.of(GameTimeToBeat.newBuilder()
                .setHastily(36_000).setNormally(72_000).setCompletely(0).setCount(12).build()));

        Optional<TimeToBeat> result = service.timeToBeat("1942", FetchBudget.UNLIMITED);

        assertThat(result).contains(new TimeToBeat(36_000L, 72_000L, null, 12));
        verify(cache).get(eq(EnrichmentSource.HLTB), eq("igdb:1942"), eq(TimeToBeat.class), any(), any());
    }

    @Test
    void igdbEmpty_empty() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token")).thenReturn(Optional.empty());

        assertThat(service.timeToBeat("26286", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void igdbRowWithoutAnyDuration_empty() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token"))
                .thenReturn(Optional.of(GameTimeToBeat.newBuilder().setCount(0).build()));

        assertThat(service.timeToBeat("26286", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void knownGame_readsIgdbFigures() throws Exception {
        when(igdbClient.findTimeToBeat("1942", "token")).thenReturn(Optional.of(GameTimeToBeat.newBuilder()
                .setNormally(72_000).setCount(3).build()));

        assertThat(service.timeToBeat("1942", FetchBudget.UNLIMITED))
                .contains(new TimeToBeat(null, 72_000L, null, 3));
    }

    @Test
    void noIgdbId_empty_cacheNotTouched() throws Exception {
        assertThat(service.timeToBeat(null, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.timeToBeat(" ", FetchBudget.UNLIMITED)).isEmpty();
        verify(cache, never()).get(any(), anyString(), any(), any(), any());
        verify(igdbClient, never()).findTimeToBeat(anyString(), anyString());
    }

    @Test
    void noIgdbToken_throws_nothingAsked() throws Exception {
        when(igdbService.getAppToken()).thenReturn("");

        assertThatThrownBy(() -> service.timeToBeat("26286", FetchBudget.UNLIMITED))
                .isInstanceOf(HltbService.IgdbUnavailableException.class);
        verify(igdbClient, never()).findTimeToBeat(anyString(), anyString());
    }

    @Test
    void igdbError_throws_nothingCached() throws Exception {
        when(igdbClient.findTimeToBeat("26286", "token")).thenThrow(mock(RequestException.class));
        GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
        HltbService withRealCache = new HltbService(
                new EnrichmentCache(repository, properties, JsonMapper.builder().build()), igdbClient, igdbService);

        assertThat(withRealCache.timeToBeat("26286", FetchBudget.UNLIMITED)).isEmpty();
        verify(repository, never()).save(any());
        assertThatThrownBy(() -> service.timeToBeat("26286", FetchBudget.UNLIMITED))
                .isInstanceOf(HltbService.IgdbUnavailableException.class);
    }
}
