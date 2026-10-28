package fr.enimaloc.catapult.api.userapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.config.I18nConfig;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.dtdd.DtddMappingService;
import fr.enimaloc.catapult.service.dtdd.DtddService;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.tw.TwLabelService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generated OpenAPI document ({@code /api/v3/api-docs}): the new routes only, and the two personal-token schemes
 * the {@code /api/me} operations accept. A {@code @WebMvcTest} slice with springdoc's own configuration, so it needs
 * neither a database nor Docker.
 */
@WebMvcTest(
        controllers = {MeGameController.class, MeBindingsController.class, MeCustomBindingsController.class,
                GamesController.class, UserApiV1Controller.class, UserApiV2Controller.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@TestPropertySource(properties = {"springdoc.api-docs.path=/api/v3/api-docs",
        "springdoc.packages-to-scan=fr.enimaloc.catapult.api.userapi"})
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration({
        org.springdoc.core.configuration.SpringDocConfiguration.class,
        org.springdoc.core.properties.SpringDocConfigProperties.class,
        org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
        org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class})
@Import({I18nConfig.class, PublicBaseUrl.class, GameInfoAssembler.class, OpenApiConfig.class,
        ApiVersionHeaderDefaultCustomizer.class, ApiUserResolver.class})
class OpenApiDocsTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @MockitoBean UserApiTokenService userApiTokenService;
    @MockitoBean UserAccountRepository userAccountRepository;
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
    @MockitoBean CustomBindingService customBindingService;
    @MockitoBean CustomBindingViews customBindingViews;

    private JsonNode docs() throws Exception {
        String body = mvc.perform(get("/api/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<String> paths(JsonNode docs) {
        return new ArrayList<>(docs.get("paths").propertyNames());
    }

    @Test
    void docs_listOnlyTheNewPaths() throws Exception {
        List<String> paths = paths(docs());

        assertThat(paths).isNotEmpty()
                .allSatisfy(path -> assertThat(path).matches("/api/(me|games)(/.*)?"))
                .contains("/api/me/game", "/api/me/custom-bindings/active", "/api/games/igdb/{igdbId}");
        assertThat(paths).noneMatch(path -> path.startsWith("/api/user") || path.startsWith("/api/game")
                && !path.startsWith("/api/games") || path.startsWith("/api/v2"));
    }

    @Test
    void docs_declareBearerAndQueryTokenSchemes() throws Exception {
        JsonNode schemes = docs().at("/components/securitySchemes");

        JsonNode bearer = schemes.get("personalToken");
        assertThat(bearer.get("type").asString()).isEqualTo("http");
        assertThat(bearer.get("scheme").asString()).isEqualTo("bearer");
        assertThat(bearer.get("description").asString()).contains("cat_").contains("catr_");
        JsonNode query = schemes.get("readToken");
        assertThat(query.get("type").asString()).isEqualTo("apiKey");
        assertThat(query.get("in").asString()).isEqualTo("query");
        assertThat(query.get("name").asString()).isEqualTo("token");
        assertThat(query.get("description").asString()).contains("catr_").contains("GET");
    }

    @Test
    void docs_requireEitherSchemeOnMeOperationsOnly() throws Exception {
        JsonNode docs = docs();

        docs.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            JsonNode security = operation.getValue().get("security");
            if (path.getKey().startsWith("/api/me")) {
                assertThat(security).as("%s %s", operation.getKey(), path.getKey()).isNotNull();
                List<String> alternatives = new ArrayList<>();
                security.forEach(requirement -> alternatives.addAll(requirement.propertyNames()));
                assertThat(alternatives).containsExactlyInAnyOrder("personalToken", "readToken");
            } else {
                assertThat(security).as("%s %s", operation.getKey(), path.getKey()).isNull();
            }
        }));
    }

    @Test
    void docs_keepTheApiVersionDocumentation() throws Exception {
        JsonNode parameters = docs().at("/paths/~1api~1me~1game/get/parameters");

        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.get("name").asString()));
        assertThat(names).contains(ApiV2.VERSION_HEADER);
    }
}
