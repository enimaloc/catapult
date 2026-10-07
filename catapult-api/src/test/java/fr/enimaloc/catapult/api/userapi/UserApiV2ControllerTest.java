package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.config.I18nConfig;
import fr.enimaloc.catapult.domain.DtddGameMapping;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.service.DtddMappingService;
import fr.enimaloc.catapult.service.DtddService;
import fr.enimaloc.catapult.service.DtddSignalService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.SteamStoreService;
import fr.enimaloc.catapult.service.TwLabelService;
import fr.enimaloc.catapult.service.WidgetTokenService;
import fr.enimaloc.catapult.service.XboxStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = UserApiV2Controller.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
// /api/user/** is public (widget-token authenticated) in ApiSecurityConfig.
@AutoConfigureMockMvc(addFilters = false)
@Import(I18nConfig.class)
class UserApiV2ControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "http://localhost:8080/api/user";

    @Autowired MockMvc mvc;

    @MockitoBean WidgetTokenService widgetTokenService;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean GameBindingRepository gameBindingRepository;
    @MockitoBean IgdbService igdbService;
    @MockitoBean IgdbGameDetailsService igdbGameDetailsService;
    @MockitoBean SteamStoreService steamStoreService;
    @MockitoBean XboxStoreService xboxStoreService;
    @MockitoBean TwLabelService twLabelService;
    @MockitoBean TwDtddTopicMappingRepository twDtddTopicMappingRepository;
    @MockitoBean DevBackdoorResolver devBackdoorResolver;
    @MockitoBean DtddMappingService dtddMappingService;
    @MockitoBean DtddSignalService dtddSignalService;
    @MockitoBean DtddService dtddService;
    @MockitoBean DtddApiClient dtddApiClient;

    private final UUID token = UUID.randomUUID();
    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(devBackdoorResolver.resolve(any())).thenReturn(Optional.empty());
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(twLabelService.resolve(anyString(), any())).thenAnswer(call -> call.getArgument(0) + "-label");
        // Like the real service: an unmapped game still gets a (dtddId-less) mapping, never null.
        when(dtddMappingService.resolve(anyString(), any())).thenReturn(new DtddGameMapping());
    }

    private void playing(GameBinding.SourceType type, String sourceId, String name) {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(new DetectedGame(sourceId, type, name)));
    }

    private static SteamStoreService.SteamStorePage steamPage(String json) {
        return JSON.readValue(json, SteamStoreService.SteamStorePage.class);
    }

    private static final SteamStoreService.SteamStorePage VALHEIM = steamPage("""
            {"type": "game", "name": "Valheim", "steam_appid": 892970, "required_age": 0, "is_free": false,
             "short_description": "A brutal exploration game.", "developers": ["Iron Gate"],
             "release_date": {"coming_soon": false, "date": "2 Feb, 2021"}}""");

    private static XboxStoreService.XboxProduct xboxProduct(String title) {
        return new XboxStoreService.XboxProduct(title, "Halo desc", "Xbox Game Studios", "343", null, null, null,
                null, Instant.EPOCH, List.of(new XboxStoreService.XboxImage("https://cover", "Poster", 1, 1, null)),
                List.of(), List.of(), List.of(), "https://store/halo");
    }

    @Nested
    class GameInfo {
        @Test
        void unknownToken_isNotFound() throws Exception {
            mvc.perform(get("/api/user/game/{uuid}", UUID.randomUUID())).andExpect(status().isNotFound());
        }

        @Test
        void nothingDetected_isNotInGame() throws Exception {
            mvc.perform(get("/api/user/game/{uuid}", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inGame").value(false))
                    .andExpect(jsonPath("$.steam").value(nullValue()));
        }

        @Test
        void steamGame_isAggregatedFromEveryPlatform() throws Exception {
            playing(GameBinding.SourceType.STEAM, "892970", "Valheim");
            when(igdbService.findByExternalAppId(GameBinding.SourceType.STEAM, "892970"))
                    .thenReturn(Optional.of(new IgdbService.IgdbGame("42", "Valheim")));
            IgdbGameDetails details = new IgdbGameDetails();
            details.setSlug("valheim");
            details.setSummary("Viking survival.");
            details.setCoverUrl("//images.igdb.com/cover.jpg");
            details.setGenres(List.of("Survival"));
            when(igdbGameDetailsService.getDetails("42")).thenReturn(Optional.of(details));
            when(steamStoreService.fetchData("892970", Locale.forLanguageTag("fr"))).thenReturn(Optional.of(VALHEIM));
            when(steamStoreService.fetchIADisclosure("892970", Locale.forLanguageTag("fr"))).thenReturn(Optional.empty());
            GameBinding binding = new GameBinding();
            binding.setId(UUID.randomUUID());
            binding.setTws(Set.of("spiders"));
            binding.setCcls(Set.of("Gore"));
            when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "892970", GameBinding.SourceType.STEAM))
                    .thenReturn(Optional.of(binding));

            mvc.perform(get("/api/user/game/{uuid}", token).param("lang", "fr"))
                    .andExpect(jsonPath("$.inGame").value(true))
                    .andExpect(jsonPath("$.igdb.igdbId").value("42"))
                    .andExpect(jsonPath("$.igdb.coverUrl").value("https://images.igdb.com/cover.jpg"))
                    .andExpect(jsonPath("$.igdb.more").value(BASE + "/igdb/42"))
                    .andExpect(jsonPath("$.steam.name").value("Valheim"))
                    .andExpect(jsonPath("$.steam.ia.hasDisclosure").value(false))
                    .andExpect(jsonPath("$.steam.more").value(BASE + "/steam/892970"))
                    .andExpect(jsonPath("$.xbox").value(nullValue()))
                    .andExpect(jsonPath("$.dtdd").value(nullValue()))
                    .andExpect(jsonPath("$.catapult.ccls[0]").value("Gore"))
                    .andExpect(jsonPath("$.catapult.more").value(BASE + "/catapult/" + binding.getId()))
                    .andExpect(jsonPath("$.name").value("Valheim"))
                    .andExpect(jsonPath("$.description").value("A brutal exploration game."));
        }

        @Test
        void steamAiDisclosure_isExtracted() throws Exception {
            playing(GameBinding.SourceType.STEAM, "892970", "Valheim");
            when(steamStoreService.fetchData(any(), any())).thenReturn(Optional.of(VALHEIM));
            when(steamStoreService.fetchIADisclosure(any(), any())).thenReturn(Optional.of("<html>no disclosure</html>"));

            mvc.perform(get("/api/user/game/{uuid}", token))
                    .andExpect(jsonPath("$.steam.ia.hasDisclosure").value(false))
                    .andExpect(jsonPath("$.steam.ia.note").value(nullValue()));
        }

        @Test
        void xboxGame() throws Exception {
            playing(GameBinding.SourceType.XBOX, "9NBLGGH2JHXJ", "Halo");
            when(xboxStoreService.fetchProduct("9NBLGGH2JHXJ", Locale.ENGLISH)).thenReturn(Optional.of(xboxProduct("Halo")));

            mvc.perform(get("/api/user/game/{uuid}", token))
                    .andExpect(jsonPath("$.xbox.name").value("Halo"))
                    .andExpect(jsonPath("$.xbox.storeUrl").value("https://store/halo"))
                    .andExpect(jsonPath("$.xbox.more").value(BASE + "/xbox/9NBLGGH2JHXJ"))
                    .andExpect(jsonPath("$.steam").value(nullValue()));
        }

        @Test
        void igdbFallsBackToTheName_andDtddIsResolvedWhenEnabled() throws Exception {
            playing(GameBinding.SourceType.MINECRAFT, null, "Minecraft");
            when(igdbService.findByName("Minecraft")).thenReturn(Optional.of(new IgdbService.IgdbGame("7", "Minecraft")));
            DtddGameMapping mapping = new DtddGameMapping();
            mapping.setDtddId(99L);
            when(dtddMappingService.resolve("7", "Minecraft")).thenReturn(mapping);
            when(dtddSignalService.getYesMostlyTopics("7", "Minecraft")).thenReturn(Set.of("Spiders"));
            when(twDtddTopicMappingRepository.findTwIdsByDtddTopicNameInIgnoreCase(Set.of("spiders")))
                    .thenReturn(Set.of("phobia_spiders"));

            mvc.perform(get("/api/user/game/{uuid}", token))
                    .andExpect(jsonPath("$.dtdd.dtddId").value(99))
                    .andExpect(jsonPath("$.dtdd.url").value("https://www.doesthedogdie.com/media/99"))
                    .andExpect(jsonPath("$.dtdd.tws[0]").value("phobia_spiders-label"))
                    .andExpect(jsonPath("$.tws").value("phobia_spiders-label"));
        }

        @Test
        void unmappedDtdd_isNull() throws Exception {
            playing(GameBinding.SourceType.MINECRAFT, null, "Minecraft");
            when(igdbService.findByName("Minecraft")).thenReturn(Optional.of(new IgdbService.IgdbGame("7", "Minecraft")));
            when(dtddMappingService.resolve("7", "Minecraft")).thenReturn(new DtddGameMapping());

            mvc.perform(get("/api/user/game/{uuid}", token)).andExpect(jsonPath("$.dtdd").value(nullValue()));
        }

        @Test
        void backdoorUuid_needsNoWidgetToken_andHasNoCatapultObject() throws Exception {
            UUID backdoor = UUID.randomUUID();
            when(devBackdoorResolver.resolve(backdoor))
                    .thenReturn(Optional.of(new DetectedGame("9NBLGGH2JHXJ", GameBinding.SourceType.XBOX, "Halo")));
            when(xboxStoreService.fetchProduct(any(), any())).thenReturn(Optional.of(xboxProduct("Halo")));

            mvc.perform(get("/api/user/game/{uuid}", backdoor))
                    .andExpect(jsonPath("$.inGame").value(true))
                    .andExpect(jsonPath("$.xbox.name").value("Halo"))
                    .andExpect(jsonPath("$.catapult").value(nullValue()));
        }

        @Test
        void acceptLanguage_isTheFallbackForLang() throws Exception {
            playing(GameBinding.SourceType.XBOX, "P", "Halo");

            mvc.perform(get("/api/user/game/{uuid}", token).header("Accept-Language", "de-DE,de;q=0.9"));
            verify(xboxStoreService).fetchProduct("P", Locale.forLanguageTag("de-de"));
        }

        @Test
        void malformedAcceptLanguage_meansEnglish() throws Exception {
            playing(GameBinding.SourceType.XBOX, "P", "Halo");

            mvc.perform(get("/api/user/game/{uuid}", token).header("Accept-Language", "@@@"));
            verify(xboxStoreService).fetchProduct("P", Locale.ENGLISH);
        }
    }

    @Test
    void summary_onlyCarriesTheCrossPlatformFields() throws Exception {
        playing(GameBinding.SourceType.XBOX, "P", "Halo");
        when(xboxStoreService.fetchProduct(any(), any())).thenReturn(Optional.of(xboxProduct("Halo")));

        mvc.perform(get("/api/user/summary/{uuid}", token))
                .andExpect(jsonPath("$.inGame").value(true))
                .andExpect(jsonPath("$.name").value("Halo"))
                .andExpect(jsonPath("$.xbox").doesNotExist());
    }

    @Test
    void summary_unknownToken_isNotFound() throws Exception {
        mvc.perform(get("/api/user/summary/{uuid}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Nested
    class Details {
        @Test
        void igdb() throws Exception {
            IgdbGameDetails details = new IgdbGameDetails();
            details.setSlug("valheim");
            details.setCoverUrl("//cover");
            details.setScreenshotUrls(List.of("//shot", "https://already"));
            details.setVideoIds(List.of("abc"));
            details.setWebsites(Map.of("official", "https://valheimgame.com"));
            details.setDlcIds(List.of("1"));
            details.setDlcNames(List.of("Ashlands"));
            when(igdbGameDetailsService.getDetails("42")).thenReturn(Optional.of(details));

            mvc.perform(get("/api/user/igdb/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://www.igdb.com/games/valheim"))
                    .andExpect(jsonPath("$.coverUrl").value("https://cover"))
                    .andExpect(jsonPath("$.screenshotUrls", containsInAnyOrder("https://shot", "https://already")))
                    .andExpect(jsonPath("$.videoUrls[0]").value("https://www.youtube.com/watch?v=abc"));

            mvc.perform(get("/api/user/igdb/0")).andExpect(status().isNotFound());
        }

        @Test
        void steam() throws Exception {
            when(steamStoreService.fetchData("892970", Locale.ENGLISH, false)).thenReturn(Optional.of(VALHEIM));
            when(steamStoreService.fetchCcls(List.of("892970"))).thenReturn(Map.of("892970", Set.of("Gore")));
            when(steamStoreService.resolveEffectiveApp("892970"))
                    .thenReturn(Optional.of(new SteamStoreService.ResolvedParentApp("100", "Base")));
            when(steamStoreService.fetchIADisclosure(any(), any())).thenReturn(Optional.empty());

            mvc.perform(get("/api/user/steam/892970"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeUrl").value("https://store.steampowered.com/app/892970"))
                    .andExpect(jsonPath("$.ccls[0]").value("Gore"));

            mvc.perform(get("/api/user/steam/1")).andExpect(status().isNotFound());
        }

        @Test
        void xbox() throws Exception {
            when(xboxStoreService.fetchProduct("P", Locale.ENGLISH)).thenReturn(Optional.of(xboxProduct("Halo")));

            mvc.perform(get("/api/user/xbox/P")).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Halo"));
            mvc.perform(get("/api/user/xbox/missing")).andExpect(status().isNotFound());
        }

        @Test
        void dtdd() throws Exception {
            when(dtddService.getTopics(99L)).thenReturn(Optional.of(
                    new DtddApiClient.DtddTopics(List.of("Spiders"), List.of("Dog dies"), List.of())));
            when(dtddApiClient.item(99L)).thenReturn(Optional.empty());

            mvc.perform(get("/api/user/dtdd/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://www.doesthedogdie.com/media/99"))
                    .andExpect(jsonPath("$.yesTopics[0]").value("Spiders"))
                    .andExpect(jsonPath("$.overview").value(nullValue()));

            mvc.perform(get("/api/user/dtdd/1")).andExpect(status().isNotFound());
        }

        @Test
        void catapult_localizesItsTriggerWarnings() throws Exception {
            GameBinding binding = new GameBinding();
            binding.setId(UUID.randomUUID());
            binding.setSourceType(GameBinding.SourceType.STEAM);
            binding.setTws(Set.of("spiders"));
            when(gameBindingRepository.findById(binding.getId())).thenReturn(Optional.of(binding));

            mvc.perform(get("/api/user/catapult/{id}", binding.getId()).param("lang", "fr"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tws[0]").value("spiders"))
                    .andExpect(jsonPath("$.twLabels[0]").value("spiders-label"));
            verify(twLabelService).resolve("spiders", Locale.forLanguageTag("fr"));

            mvc.perform(get("/api/user/catapult/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        }
    }
}
