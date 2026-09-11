package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.config.I18nConfig;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.SteamStoreService;
import fr.enimaloc.catapult.service.WidgetTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiGameInfoController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
// /api/game/** is permitAll (widget-token authenticated, no JWT) in ApiSecurityConfig,
// so disabling filters here does not hide an auth regression.
@AutoConfigureMockMvc(addFilters = false)
@Import(I18nConfig.class)
class ApiGameInfoControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean WidgetTokenService widgetTokenService;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean GameBindingRepository gameBindingRepository;
    @MockitoBean IgdbService igdbService;
    @MockitoBean IgdbGameDetailsService igdbGameDetailsService;
    @MockitoBean SteamStoreService steamStoreService;

    @Test
    void gameInfo_unknownToken_returns404() throws Exception {
        UUID token = UUID.randomUUID();
        when(widgetTokenService.resolve(token)).thenReturn(Optional.empty());

        mvc.perform(get("/api/game/{uuid}", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void gameInfo_noCurrentGame_returns404() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        mvc.perform(get("/api/game/{uuid}", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void gameInfo_steamGameWithBindingAndIgdbDetails_returnsNormalizedPayload() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        DetectedGame detected = new DetectedGame("440", GameBinding.SourceType.STEAM, "Team Fortress 2");

        GameBinding binding = new GameBinding();
        binding.setUser(user);
        binding.setSourceId("440");
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceName("Team Fortress 2");
        binding.setTwitchGameId("658");
        binding.setTwitchGameName("Team Fortress 2");
        binding.getTws().add("violence");
        binding.getTws().add("gore");
        binding.getCcls().add("blood");
        binding.getCcls().add("alcohol");

        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("1234");
        details.setSlug("team-fortress-2");
        details.setSummary("A team-based multiplayer shooter.");
        details.setPlatforms(java.util.List.of("PC", "Xbox 360"));
        details.setRating(85.0);
        details.setAggregatedRating(78.5);
        details.setFirstReleaseDate(java.time.Instant.parse("2007-10-10T00:00:00Z"));

        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "440", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.of(binding));
        when(igdbService.findByExternalAppId(GameBinding.SourceType.STEAM, "440"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("1234", "Team Fortress 2")));
        when(igdbGameDetailsService.getDetails("1234")).thenReturn(Optional.of(details));

        mvc.perform(get("/api/game/{uuid}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Team Fortress 2"))
                .andExpect(jsonPath("$.igdbUrl").value("https://www.igdb.com/games/team-fortress-2"))
                .andExpect(jsonPath("$.storeName").value("Steam"))
                .andExpect(jsonPath("$.storeUrl").value("https://store.steampowered.com/app/440"))
                .andExpect(jsonPath("$.description").value("A team-based multiplayer shooter."))
                .andExpect(jsonPath("$.twitchGameId").value("658"))
                .andExpect(jsonPath("$.twitchGameName").value("Team Fortress 2"))
                .andExpect(jsonPath("$.sourceType").value("STEAM"))
                .andExpect(jsonPath("$.tws", org.hamcrest.Matchers.containsInAnyOrder("violence", "gore")))
                .andExpect(jsonPath("$.twsJoined").value("gore, violence"))
                .andExpect(jsonPath("$.ccls", org.hamcrest.Matchers.containsInAnyOrder("blood", "alcohol")))
                .andExpect(jsonPath("$.cclsJoined").value("alcohol, blood"))
                .andExpect(jsonPath("$.platforms", org.hamcrest.Matchers.contains("PC", "Xbox 360")))
                .andExpect(jsonPath("$.platformsJoined").value("PC, Xbox 360"))
                .andExpect(jsonPath("$.rating").value(85.0))
                .andExpect(jsonPath("$.aggregatedRating").value(78.5))
                .andExpect(jsonPath("$.releaseDate").exists());
    }

    @Test
    void gameInfo_steamDescriptionAvailable_takesPriorityOverIgdbSummary() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        DetectedGame detected = new DetectedGame("440", GameBinding.SourceType.STEAM, "Team Fortress 2");

        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("1234");
        details.setSummary("IGDB generic summary.");

        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "440", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.empty());
        when(igdbService.findByExternalAppId(GameBinding.SourceType.STEAM, "440"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("1234", "Team Fortress 2")));
        when(igdbGameDetailsService.getDetails("1234")).thenReturn(Optional.of(details));
        when(steamStoreService.fetchDescription("440", Locale.FRENCH))
                .thenReturn(Optional.of("Description Steam en français."));

        mvc.perform(get("/api/game/{uuid}?lang=fr", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Description Steam en français."));
    }

    @Test
    void gameInfo_noStoreDescription_fallsBackToIgdbSummary() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        DetectedGame detected = new DetectedGame("440", GameBinding.SourceType.STEAM, "Team Fortress 2");

        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("1234");
        details.setSummary("IGDB generic summary.");

        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "440", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.empty());
        when(igdbService.findByExternalAppId(GameBinding.SourceType.STEAM, "440"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("1234", "Team Fortress 2")));
        when(igdbGameDetailsService.getDetails("1234")).thenReturn(Optional.of(details));
        when(steamStoreService.fetchDescription("440", Locale.ENGLISH)).thenReturn(Optional.empty());

        mvc.perform(get("/api/game/{uuid}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("IGDB generic summary."));
    }

    @Test
    void gameInfo_noBindingOrIgdbMatch_returnsPartialPayload() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        DetectedGame detected = new DetectedGame(null, GameBinding.SourceType.MANUAL, "Some Unlisted Game");

        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, null, GameBinding.SourceType.MANUAL))
                .thenReturn(Optional.empty());
        when(igdbService.findByName("Some Unlisted Game")).thenReturn(Optional.empty());

        mvc.perform(get("/api/game/{uuid}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Some Unlisted Game"))
                .andExpect(jsonPath("$.igdbUrl").doesNotExist())
                .andExpect(jsonPath("$.storeName").value("Manual"))
                .andExpect(jsonPath("$.storeUrl").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.twitchGameId").doesNotExist())
                .andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.tws").isEmpty())
                .andExpect(jsonPath("$.twsJoined").value(""))
                .andExpect(jsonPath("$.ccls").isEmpty())
                .andExpect(jsonPath("$.cclsJoined").value(""))
                .andExpect(jsonPath("$.platforms").isEmpty())
                .andExpect(jsonPath("$.platformsJoined").value(""))
                .andExpect(jsonPath("$.rating").doesNotExist())
                .andExpect(jsonPath("$.aggregatedRating").doesNotExist())
                .andExpect(jsonPath("$.releaseDate").doesNotExist());
    }

    @Test
    void gameInfo_langParam_localizesStoreName() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        DetectedGame detected = new DetectedGame(null, GameBinding.SourceType.MANUAL, "Some Unlisted Game");

        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, null, GameBinding.SourceType.MANUAL))
                .thenReturn(Optional.empty());
        when(igdbService.findByName("Some Unlisted Game")).thenReturn(Optional.empty());

        mvc.perform(get("/api/game/{uuid}?lang=fr", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Manuel"));
    }
}
