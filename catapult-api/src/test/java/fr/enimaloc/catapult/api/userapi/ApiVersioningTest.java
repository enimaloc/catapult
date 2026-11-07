package fr.enimaloc.catapult.api.userapi;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.enimaloc.catapult.config.I18nConfig;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.dtdd.DtddMappingService;
import fr.enimaloc.catapult.service.dtdd.DtddService;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.tw.TwLabelService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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

/** How the API version is resolved: the {@code X-API-Version} header first, then {@code ?version=}. */
@WebMvcTest(
        controllers = UserApiV2Controller.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
@Import({I18nConfig.class, PublicBaseUrl.class, GameInfoAssembler.class})
class ApiVersioningTest {

    private static final String IGDB = "/api/user/igdb/42";

    @Autowired MockMvc mvc;

    @MockitoBean UserApiTokenService userApiTokenService;
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

    @BeforeEach
    void setUp() {
        IgdbGameDetails details = new IgdbGameDetails();
        details.setSlug("valheim");
        when(igdbGameDetailsService.getDetails("42")).thenReturn(Optional.of(details));
    }

    @Test
    void versionQueryParam_isAccepted() throws Exception {
        mvc.perform(get(IGDB).queryParam(ApiV2.VERSION_PARAM, ApiV2.VERSION)).andExpect(status().isOk());
    }

    @Test
    void unsupportedQueryVersion_is400() throws Exception {
        mvc.perform(get(IGDB).queryParam(ApiV2.VERSION_PARAM, "9")).andExpect(status().isBadRequest());
    }

    @Test
    void header_winsOverQueryParam() throws Exception {
        mvc.perform(get(IGDB).header(ApiV2.VERSION_HEADER, ApiV2.VERSION).queryParam(ApiV2.VERSION_PARAM, "9"))
                .andExpect(status().isOk());
    }

    @Test
    void noVersion_resolvesToTheDefault() throws Exception {
        mvc.perform(get(IGDB)).andExpect(status().isOk());
    }
}
