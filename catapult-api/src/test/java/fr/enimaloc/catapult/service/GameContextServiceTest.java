package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GameContextServiceTest {

    @Mock
    private GameStateService gameStateService;

    @Mock
    private IgdbService igdbService;

    @Mock
    private IgdbGameDetailsService igdbGameDetailsService;

    @Mock
    private IgdbGameCclRepository igdbGameCclRepository;

    @Mock
    private GameBindingRepository gameBindingRepository;

    @Mock
    private UserSettingsRepository userSettingsRepository;

    @Mock
    private TwLabelService twLabelService;

    private GameContextService service;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new GameContextService(gameStateService, igdbService, igdbGameDetailsService,
            igdbGameCclRepository, gameBindingRepository, userSettingsRepository, twLabelService);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void steam_game_hydrates_full_context() {
        DetectedGame detected = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));

        IgdbGameDetails details = new IgdbGameDetails();
        details.setSlug("dota-2");
        details.setSummary("MOBA");
        details.setWebsites(Map.of("steam", "https://steam/570"));
        details.setFirstReleaseDate(Instant.parse("2013-07-09T00:00:00Z"));
        when(igdbGameDetailsService.getDetails("8173")).thenReturn(Optional.of(details));

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
        assertThat(ctx.activeTws()).isEmpty();
        assertThat(ctx.twLabels()).isEmpty();
    }

    @Test
    void igdb_miss_keeps_name_from_detected() {
        DetectedGame detected = new DetectedGame("xbox-id-1", GameBinding.SourceType.XBOX, "Unknown");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findByName("Unknown")).thenReturn(Optional.empty());

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
        assertThat(ctx.activeTws()).isEmpty();
        assertThat(ctx.twLabels()).isEmpty();
    }

    @Test
    void no_game_in_state_returns_empty() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        assertThat(service.get(user)).isEmpty();
    }

    @Test
    void game_change_invalidates_cache() {
        DetectedGame first = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        DetectedGame second = new DetectedGame("440", GameBinding.SourceType.STEAM, "Team Fortress 2");
        when(gameStateService.getLastKnownGame(user))
            .thenReturn(Optional.of(first))
            .thenReturn(Optional.of(second));
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));
        when(igdbService.findBySteamAppId("440"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("206", "Team Fortress 2")));
        when(igdbGameDetailsService.getDetails(any())).thenReturn(Optional.empty());

        assertThat(service.get(user).orElseThrow().name()).isEqualTo("Dota 2");
        assertThat(service.get(user).orElseThrow().name()).isEqualTo("Team Fortress 2");
        verify(igdbService).findBySteamAppId("570");
        verify(igdbService).findBySteamAppId("440");
    }

    @Test
    void cache_hit_skips_igdb_lookup_when_same_game() {
        DetectedGame detected = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));
        when(igdbGameDetailsService.getDetails("8173")).thenReturn(Optional.empty());

        service.get(user);
        service.get(user);
        service.get(user);

        verify(igdbService, times(1)).findBySteamAppId("570");
    }

    @Test
    void igdb_throws_returns_minimal_context_with_name() {
        DetectedGame detected = new DetectedGame("zwaard-id", GameBinding.SourceType.XBOX, "Zwaard");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findByName("Zwaard")).thenThrow(new RuntimeException("IGDB down"));

        GameContext ctx = service.get(user).orElseThrow();
        assertThat(ctx.name()).isEqualTo("Zwaard");
        assertThat(ctx.detected()).isSameAs(detected);
        assertThat(ctx.igdbId()).isNull();
        assertThat(ctx.activeTws()).isEmpty();
    }

    @Test
    void activeTws_filteredByBlockedTws_andLabelsResolved_whenFeatureEnabled() {
        DetectedGame detected = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));
        when(igdbGameDetailsService.getDetails("8173")).thenReturn(Optional.empty());

        GameBinding binding = new GameBinding();
        binding.setTwEnabled(true);
        binding.setTws(new HashSet<>(Set.of("violence_graphic", "death_of_animal", "vomit")));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(
                user, "570", GameBinding.SourceType.STEAM))
            .thenReturn(Optional.of(binding));

        UserSettings settings = new UserSettings();
        settings.setTwFeatureEnabled(true);
        settings.setBlockedTws(new HashSet<>(Set.of("vomit")));
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        when(twLabelService.resolve(any(String.class), any(Locale.class)))
            .thenAnswer(inv -> "label-" + inv.getArgument(0));

        GameContext ctx = service.get(user).orElseThrow();
        assertThat(ctx.activeTws()).containsExactlyInAnyOrder("violence_graphic", "death_of_animal");
        assertThat(ctx.activeTws()).doesNotContain("vomit");
        assertThat(ctx.twLabels())
            .containsEntry("violence_graphic", "label-violence_graphic")
            .containsEntry("death_of_animal", "label-death_of_animal")
            .doesNotContainKey("vomit");
    }

    @Test
    void activeTws_emptyWhenTwDisabledOnBinding() {
        DetectedGame detected = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));
        when(igdbGameDetailsService.getDetails("8173")).thenReturn(Optional.empty());

        GameBinding binding = new GameBinding();
        binding.setTwEnabled(false);
        binding.setTws(new HashSet<>(Set.of("violence_graphic")));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(
                user, "570", GameBinding.SourceType.STEAM))
            .thenReturn(Optional.of(binding));

        UserSettings settings = new UserSettings();
        settings.setTwFeatureEnabled(true);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        GameContext ctx = service.get(user).orElseThrow();
        assertThat(ctx.activeTws()).isEmpty();
        assertThat(ctx.twLabels()).isEmpty();
    }

    @Test
    void activeTws_emptyWhenUserFeatureDisabled() {
        DetectedGame detected = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findBySteamAppId("570"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("8173", "Dota 2")));
        when(igdbGameDetailsService.getDetails("8173")).thenReturn(Optional.empty());

        GameBinding binding = new GameBinding();
        binding.setTwEnabled(true);
        binding.setTws(new HashSet<>(Set.of("violence_graphic")));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(
                user, "570", GameBinding.SourceType.STEAM))
            .thenReturn(Optional.of(binding));

        UserSettings settings = new UserSettings();
        settings.setTwFeatureEnabled(false);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        GameContext ctx = service.get(user).orElseThrow();
        assertThat(ctx.activeTws()).isEmpty();
        assertThat(ctx.twLabels()).isEmpty();
    }
}
