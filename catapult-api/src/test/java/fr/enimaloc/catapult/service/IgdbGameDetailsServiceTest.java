package fr.enimaloc.catapult.service;

import com.google.protobuf.Timestamp;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.repository.IgdbGameDetailsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;
import proto.Website;
import proto.WebsiteCategoryEnum;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IgdbGameDetailsServiceTest {

    @Mock IgdbGameDetailsRepository repository;
    @Mock IgdbClient igdbClient;
    @Mock IgdbService igdbService;
    @Mock Executor refreshExecutor;

    private SimpleMeterRegistry meterRegistry;
    private IgdbGameDetailsService service;

    private static final String IGDB_ID = "12345";
    private static final String TOKEN = "tok";

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        service = new IgdbGameDetailsService(repository, igdbClient, igdbService, meterRegistry, refreshExecutor);
        ReflectionTestUtils.setField(service, "cacheTtlHours", 168);
        when(igdbService.getAppToken()).thenReturn(TOKEN);
    }

    @Test
    void cache_hit_fresh_returns_repo_value_without_igdb_call() {
        IgdbGameDetails fresh = new IgdbGameDetails();
        fresh.setIgdbId(IGDB_ID);
        fresh.setSlug("fresh-slug");
        fresh.setFetchedAt(Instant.now().minusSeconds(60));
        when(repository.findById(IGDB_ID)).thenReturn(Optional.of(fresh));

        Optional<IgdbGameDetails> result = service.getDetails(IGDB_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getSlug()).isEqualTo("fresh-slug");
        verifyNoInteractions(igdbClient);
        verifyNoInteractions(refreshExecutor);
        assertThat(meterRegistry.counter("catapult.igdb.details.serve", "outcome", "fresh").count())
            .isEqualTo(1.0);
    }

    @Test
    void cache_miss_fetches_from_igdb_and_persists() {
        when(repository.findById(IGDB_ID)).thenReturn(Optional.empty());
        Game game = Game.newBuilder()
            .setId(Long.parseLong(IGDB_ID))
            .setSlug("new-slug")
            .setSummary("A summary.")
            .build();
        when(igdbClient.fetchGameDetails(IGDB_ID, TOKEN)).thenReturn(Optional.of(game));
        when(repository.save(any(IgdbGameDetails.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<IgdbGameDetails> result = service.getDetails(IGDB_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getSlug()).isEqualTo("new-slug");
        assertThat(result.get().getSummary()).isEqualTo("A summary.");

        ArgumentCaptor<IgdbGameDetails> captor = ArgumentCaptor.forClass(IgdbGameDetails.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIgdbId()).isEqualTo(IGDB_ID);
        assertThat(captor.getValue().getSlug()).isEqualTo("new-slug");
        assertThat(captor.getValue().getFetchedAt()).isNotNull();
        assertThat(meterRegistry.counter("catapult.igdb.details.serve", "outcome", "miss").count())
            .isEqualTo(1.0);
    }

    @Test
    void cache_stale_serves_stale_and_triggers_async_refresh() {
        IgdbGameDetails stale = new IgdbGameDetails();
        stale.setIgdbId(IGDB_ID);
        stale.setSlug("stale-slug");
        stale.setFetchedAt(Instant.now().minus(java.time.Duration.ofDays(30)));
        when(repository.findById(IGDB_ID)).thenReturn(Optional.of(stale));

        Optional<IgdbGameDetails> result = service.getDetails(IGDB_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getSlug()).isEqualTo("stale-slug");
        verify(refreshExecutor, times(1)).execute(any(Runnable.class));
        verify(igdbClient, never()).fetchGameDetails(anyString(), anyString());
        assertThat(meterRegistry.counter("catapult.igdb.details.serve", "outcome", "stale").count())
            .isEqualTo(1.0);
    }

    @Test
    void igdb_ko_with_cache_returns_stale_value() {
        IgdbGameDetails stale = new IgdbGameDetails();
        stale.setIgdbId(IGDB_ID);
        stale.setSlug("stale-slug");
        stale.setFetchedAt(Instant.now().minus(java.time.Duration.ofDays(30)));
        when(repository.findById(IGDB_ID)).thenReturn(Optional.of(stale));
        when(igdbClient.fetchGameDetails(IGDB_ID, TOKEN)).thenReturn(Optional.empty());

        Optional<IgdbGameDetails> result = service.getDetails(IGDB_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getSlug()).isEqualTo("stale-slug");
    }

    @Test
    void igdb_ko_no_cache_returns_empty_and_does_not_save() {
        when(repository.findById(IGDB_ID)).thenReturn(Optional.empty());
        when(igdbClient.fetchGameDetails(IGDB_ID, TOKEN)).thenReturn(Optional.empty());

        Optional<IgdbGameDetails> result = service.getDetails(IGDB_ID);

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void cache_miss_extracts_websites_and_external_games() {
        when(repository.findById(IGDB_ID)).thenReturn(Optional.empty());
        Website official = Website.newBuilder()
            .setCategory(WebsiteCategoryEnum.WEBSITE_OFFICIAL)
            .setUrl("https://example.com")
            .build();
        ExternalGameSource source = ExternalGameSource.newBuilder()
            .setId(1L)
            .setName("Steam")
            .build();
        ExternalGame ext = ExternalGame.newBuilder()
            .setUid("440")
            .setExternalGameSource(source)
            .build();
        Game game = Game.newBuilder()
            .setId(Long.parseLong(IGDB_ID))
            .setSlug("tf2")
            .setFirstReleaseDate(Timestamp.newBuilder().setSeconds(1234567890L).build())
            .addWebsites(official)
            .addExternalGames(ext)
            .build();
        when(igdbClient.fetchGameDetails(IGDB_ID, TOKEN)).thenReturn(Optional.of(game));
        when(repository.save(any(IgdbGameDetails.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<IgdbGameDetails> result = service.getDetails(IGDB_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getWebsites()).containsEntry("official", "https://example.com");
        assertThat(result.get().getWebsites()).containsEntry("steam", "440");
        assertThat(result.get().getFirstReleaseDate()).isEqualTo(Instant.ofEpochSecond(1234567890L));
    }
}
