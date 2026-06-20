package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GameContextServiceTest {

    @Mock
    private IgdbService igdbService;

    @Mock
    private IgdbGameDetailsService igdbGameDetailsService;

    @Mock
    private IgdbGameCclRepository igdbGameCclRepository;

    private GameContextService service;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new GameContextService(igdbService, igdbGameDetailsService, igdbGameCclRepository, null);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void on_game_detected_steam_hydrates_full_context() {
        DetectedGame detected = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));

        IgdbGameDetails details = new IgdbGameDetails();
        details.setSlug("dota-2");
        details.setSummary("MOBA");
        details.setWebsites(Map.of("steam", "https://steam/570"));
        details.setFirstReleaseDate(Instant.parse("2013-07-09T00:00:00Z"));
        when(igdbGameDetailsService.getDetails("8173")).thenReturn(Optional.of(details));

        service.onGameDetected(new GameDetectedEvent(this, user, detected));

        Optional<GameContext> ctxOpt = service.get(user);
        assertThat(ctxOpt).isPresent();
        GameContext ctx = ctxOpt.get();
        assertThat(ctx.name()).isEqualTo("Dota 2");
        assertThat(ctx.igdbId()).isEqualTo("8173");
        assertThat(ctx.summary()).isEqualTo("MOBA");
        assertThat(ctx.releaseDate()).isEqualTo(LocalDate.of(2013, 7, 9));
        assertThat(ctx.activeStoreUrl()).isEqualTo("https://steam/570");
        assertThat(ctx.igdbSlug()).isEqualTo("dota-2");
        assertThat(ctx.stores()).containsEntry("steam", "https://steam/570");
        assertThat(ctx.detected()).isSameAs(detected);
    }

    @Test
    void igdb_miss_keeps_name_from_detected() {
        DetectedGame detected = new DetectedGame("xbox-id-1", GameBinding.SourceType.XBOX, "Unknown");
        when(igdbService.findByName("Unknown")).thenReturn(Optional.empty());

        service.onGameDetected(new GameDetectedEvent(this, user, detected));

        Optional<GameContext> ctxOpt = service.get(user);
        assertThat(ctxOpt).isPresent();
        GameContext ctx = ctxOpt.get();
        assertThat(ctx.name()).isEqualTo("Unknown");
        assertThat(ctx.igdbId()).isNull();
        assertThat(ctx.summary()).isNull();
        assertThat(ctx.releaseDate()).isNull();
        assertThat(ctx.activeStoreUrl()).isNull();
        assertThat(ctx.igdbSlug()).isNull();
        assertThat(ctx.stores()).isEmpty();
    }

    @Test
    void no_game_detected_clears_context() {
        DetectedGame detected = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));

        IgdbGameDetails details = new IgdbGameDetails();
        details.setSlug("dota-2");
        details.setSummary("MOBA");
        details.setWebsites(Map.of("steam", "https://steam/570"));
        details.setFirstReleaseDate(Instant.parse("2013-07-09T00:00:00Z"));
        when(igdbGameDetailsService.getDetails("8173")).thenReturn(Optional.of(details));

        service.onGameDetected(new GameDetectedEvent(this, user, detected));
        assertThat(service.get(user)).isPresent();

        service.onNoGameDetected(new NoGameDetectedEvent(this, user));
        assertThat(service.get(user)).isEmpty();
    }
}
