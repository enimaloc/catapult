package fr.enimaloc.catapult.api.userapi;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.enimaloc.catapult.config.I18nConfig;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.experiment.ApiDefaultVersionExperiment;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.dtdd.DtddMappingService;
import fr.enimaloc.catapult.service.dtdd.DtddService;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.tw.TwLabelService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the API version is resolved: the {@code X-API-Version} header first, then {@code ?version=}, then the
 * caller's variant of the default version experiment.
 */
@WebMvcTest(
        controllers = UserApiV2Controller.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
@Import({I18nConfig.class, PublicBaseUrl.class, GameInfoAssembler.class, ExperimentDefaultVersionResolver.class,
        ApiVersioningTest.VersionProbe.class})
class ApiVersioningTest {

    private static final String IGDB = "/api/user/igdb/42";
    private static final String PROBE = "/test/version-probe";

    @Autowired MockMvc mvc;
    @Autowired ExperimentDefaultVersionResolver defaultVersionResolver;

    @MockitoBean ExperimentService experimentService;
    @MockitoBean UserAccountRepository userAccountRepository;

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
        defaultVersionResolver.clear();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A test route with a baseline mapping ("2+") and a v3 one, answering which of them served the request. */
    @RestController
    static class VersionProbe {
        @GetMapping(path = PROBE, version = ApiV2.BASELINE)
        String baseline() {
            return "baseline";
        }

        @GetMapping(path = PROBE, version = ApiV2.VERSION_3)
        String v3() {
            return "v3";
        }
    }

    /** A member authenticated by their personal API token, in {@code variant} of the default version experiment. */
    private void memberIn(String variant) {
        UserAccount member = new UserAccount();
        member.setId(UUID.randomUUID());
        when(userAccountRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(experimentService.getVariantKey(member, ApiDefaultVersionExperiment.KEY)).thenReturn(Optional.of(variant));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                member.getId(), null, List.of(new SimpleGrantedAuthority("API_TOKEN"))));
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

    @Test
    void v3Request_picksTheV3Mapping_overTheBaseline() throws Exception {
        mvc.perform(get(PROBE).header(ApiV2.VERSION_HEADER, "3")).andExpect(content().string("v3"));
        mvc.perform(get(PROBE).header(ApiV2.VERSION_HEADER, "2")).andExpect(content().string("baseline"));
    }

    @Test
    void explicitHeader_winsOverExperiment() throws Exception {
        memberIn(ApiDefaultVersionExperiment.V3_VARIANT);

        mvc.perform(get(PROBE).header(ApiV2.VERSION_HEADER, "2")).andExpect(content().string("baseline"));
        mvc.perform(get(PROBE).queryParam(ApiV2.VERSION_PARAM, "2")).andExpect(content().string("baseline"));
    }

    @Test
    void member_inV3Variant_noHeader_getsV3() throws Exception {
        memberIn(ApiDefaultVersionExperiment.V3_VARIANT);

        mvc.perform(get(PROBE)).andExpect(content().string("v3"));
    }

    @Test
    void member_inV2Variant_noHeader_getsV2() throws Exception {
        memberIn(ApiDefaultVersionExperiment.V2_VARIANT);

        mvc.perform(get(PROBE)).andExpect(content().string("baseline"));
    }

    @Test
    void anonymous_noHeader_followsMajorityVariant() throws Exception {
        when(experimentService.getHeaviestVariantKey(ApiDefaultVersionExperiment.KEY))
                .thenReturn(Optional.of(ApiDefaultVersionExperiment.V2_VARIANT));
        mvc.perform(get(PROBE)).andExpect(content().string("baseline"));

        defaultVersionResolver.clear();
        when(experimentService.getHeaviestVariantKey(ApiDefaultVersionExperiment.KEY))
                .thenReturn(Optional.of(ApiDefaultVersionExperiment.V3_VARIANT));
        mvc.perform(get(PROBE)).andExpect(content().string("v3"));
    }

    @Test
    void legacyRoute_answersInV3() throws Exception {
        mvc.perform(get(IGDB).header(ApiV2.VERSION_HEADER, "3")).andExpect(status().isOk());
    }

    @Test
    void unknownVersion_stillRejected() throws Exception {
        mvc.perform(get(IGDB).header(ApiV2.VERSION_HEADER, "9")).andExpect(status().isBadRequest());
    }
}
