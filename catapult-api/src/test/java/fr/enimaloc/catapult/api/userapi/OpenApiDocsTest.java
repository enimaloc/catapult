package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.IpFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardGameDataService;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStatsService;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticService;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsStatsService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunGameService;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamStatsService;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsService;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.prices.PricesService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivityService;
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
                MeStatsController.class,
                GamesController.class, EnrichmentGamesController.class, UserApiV1Controller.class, UserApiV2Controller.class},
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
        ApiVersionParameterCustomizer.class, ApiUserResolver.class})
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
    @MockitoBean IpFetchBudgets ipFetchBudgets;
    @MockitoBean HltbService hltbService;
    @MockitoBean ProtonDbService protonDbService;
    @MockitoBean SteamDeckService steamDeckService;
    @MockitoBean SteamGridDbService steamGridDbService;
    @MockitoBean PcGamingWikiService pcGamingWikiService;
    @MockitoBean PricesService pricesService;
    @MockitoBean OpenCriticService openCriticService;
    @MockitoBean TwitchActivityService twitchActivityService;
    @MockitoBean SpeedrunGameService speedrunGameService;
    @MockitoBean SteamStatsService steamStatsService;
    @MockitoBean XboxStatsService xboxStatsService;
    @MockitoBean RetroAchievementsStatsService retroAchievementsStatsService;
    @MockitoBean MemberFetchBudgets memberFetchBudgets;
    @MockitoBean BlizzardStatsService blizzardStatsService;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.chess.ChessStatsService chessStatsService;
    @MockitoBean BlizzardGameDataService blizzardGameDataService;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.bungie.BungieStatsService bungieStatsService;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.riot.RiotStatsService riotStatsService;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.faceit.FaceitStatsService faceitStatsService;

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

    /** The read-only token opens the reads alone: a write takes the bearer scheme only. */
    @Test
    void docs_requireEitherSchemeOnMeReads_andTheBearerOneOnMeWrites() throws Exception {
        JsonNode docs = docs();

        docs.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            JsonNode security = operation.getValue().get("security");
            if (path.getKey().startsWith("/api/me")) {
                assertThat(security).as("%s %s", operation.getKey(), path.getKey()).isNotNull();
                List<String> alternatives = new ArrayList<>();
                security.forEach(requirement -> alternatives.addAll(requirement.propertyNames()));
                if (operation.getKey().equals("get") || operation.getKey().equals("head")) {
                    assertThat(alternatives).as("%s %s", operation.getKey(), path.getKey())
                            .containsExactlyInAnyOrder("personalToken", "readToken");
                } else {
                    assertThat(alternatives).as("%s %s", operation.getKey(), path.getKey())
                            .containsExactly("personalToken");
                }
            } else {
                assertThat(security).as("%s %s", operation.getKey(), path.getKey()).isNull();
            }
        }));
    }

    /** The schemes alone only show as a padlock: the page's header tells how to send a token, with examples. */
    @Test
    void docs_explainHowToSendTheTokenInTheirHeader() throws Exception {
        JsonNode info = docs().get("info");

        assertThat(info.get("title").asString()).isEqualTo("Catapult API");
        assertThat(info.get("description").asString())
                .contains("Authorization: Bearer cat_")
                .contains("Authorization: Bearer catr_")
                .contains("?token=catr_")
                .contains("curl ");
    }

    /** An opened operation names the token it takes, how to send it, and that the read-only one opens no write. */
    @Test
    void docs_tellEachMeOperationWhichTokenItTakes() throws Exception {
        JsonNode docs = docs();

        String read = docs.at("/paths/~1api~1me~1game/get/description").asString();
        assertThat(read).contains("Authorization: Bearer").contains("cat_").contains("catr_").contains("?token=");
        String write = docs.at("/paths/~1api~1me~1custom-bindings~1active/put/description").asString();
        assertThat(write).contains("Authorization: Bearer cat_").doesNotContain("?token=");
        assertThat(docs.at("/paths/~1api~1games~1igdb~1{igdbId}/get/description").asString())
                .doesNotContain("Authorization");
    }

    @Test
    void docs_keepTheApiVersionDocumentation() throws Exception {
        JsonNode parameters = docs().at("/paths/~1api~1me~1game/get/parameters");

        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.get("name").asString()));
        assertThat(names).contains(ApiV2.VERSION_HEADER);
    }

    /** The version header lists the versions each route answers, with no fixed default: the server assigns it. */
    @Test
    void docs_listTheVersionsEachRouteAnswers_withoutAFixedDefault() throws Exception {
        JsonNode docs = docs();

        assertThat(versionHeader(docs, "/paths/~1api~1me~1game/get/parameters").get("schema").get("enum").toString())
                .isEqualTo("[\"2\",\"3\"]");
        assertThat(versionHeader(docs, "/paths/~1api~1games~1igdb~1{igdbId}/get/parameters").get("schema")
                .get("enum").toString()).isEqualTo("[\"2\",\"3\"]");
        JsonNode v3Only = versionHeader(docs, "/paths/~1api~1games~1protondb~1{appId}/get/parameters");
        assertThat(v3Only.get("schema").get("enum").toString()).isEqualTo("[\"3\"]");
        assertThat(v3Only.get("schema").has("default")).isFalse();
        assertThat(v3Only.get("description").asString()).contains("assigns").contains("explicit value always wins");
    }

    /** The enrichment detail routes are documented, public, each with its version 3 header. */
    @Test
    void docs_listTheEnrichmentDetailRoutes() throws Exception {
        List<String> paths = paths(docs());

        assertThat(paths).contains("/api/games/hltb/{igdbId}", "/api/games/protondb/{appId}",
                "/api/games/steamdeck/{appId}", "/api/games/steamgriddb/{sgdbGameId}",
                "/api/games/pcgamingwiki/{pageId}", "/api/games/prices/{itadId}", "/api/games/opencritic/{ocId}",
                "/api/games/twitch/{twitchGameId}", "/api/games/speedrun/{srcGameId}",
                "/api/games/blizzard/hearthstone/deck/{code}", "/api/games/blizzard/wow/token/{region}");
    }

    /**
     * The personal stats routes are documented, version 3 only; the achievement schema keeps its own name (the Steam
     * store page has an {@code Achievement} too, which springdoc would otherwise merge it with).
     */
    @Test
    void docs_listThePersonalStatsRoutes() throws Exception {
        JsonNode docs = docs();

        assertThat(paths(docs)).contains("/api/me/stats", "/api/me/stats/steam/{appId}",
                "/api/me/stats/xbox/{titleId}", "/api/me/stats/retroachievements/{gameId}",
                "/api/me/stats/retroachievements", "/api/me/stats/blizzard/{game}", "/api/me/stats/riot/{game}",
                "/api/me/stats/faceit/{game}", "/api/me/stats/chess");
        assertThat(versionHeader(docs, "/paths/~1api~1me~1stats~1steam~1{appId}/get/parameters").get("schema")
                .get("enum").toString()).isEqualTo("[\"3\"]");
        JsonNode schemas = docs.at("/components/schemas");
        assertThat(schemas.has("PersonalAchievement")).isTrue();
        assertThat(schemas.at("/Achievement/properties").has("highlighted")).isTrue();
        assertThat(schemas.at("/StatsObject/properties").has("detectedOn")).isFalse();
        // the detail routes answer the aggregate's objects, with every achievement
        for (String name : List.of("SteamStats", "XboxStats", "RaStats")) {
            assertThat(schemas.at("/" + name + "/properties").has("all")).as(name).isTrue();
        }
        assertThat(schemas.has("PlatformStats")).isFalse();
        assertThat(docs.at("/paths/~1api~1me~1stats~1steam~1{appId}/get/responses/200/content").toString())
                .contains("SteamStats");
        // Blizzard: the detail answers the aggregate's own record; the public data has its own object
        assertThat(docs.at("/paths/~1api~1me~1stats~1blizzard~1{game}/get/responses/200/content").toString())
                .contains("BlizzardStats");
        assertThat(schemas.at("/StatsObject/properties").has("blizzard")).isTrue();
        assertThat(schemas.at("/BlizzardStats/properties").has("more")).isTrue();
        // Riot: the same, the detail answering the aggregate's own record
        assertThat(docs.at("/paths/~1api~1me~1stats~1riot~1{game}/get/responses/200/content").toString())
                .contains("RiotStats");
        assertThat(schemas.at("/StatsObject/properties").has("riot")).isTrue();
        assertThat(schemas.at("/RiotStats/properties").has("more")).isTrue();
        // FACEIT: the same, the detail answering the aggregate's own record
        assertThat(docs.at("/paths/~1api~1me~1stats~1faceit~1{game}/get/responses/200/content").toString())
                .contains("FaceitStats");
        assertThat(schemas.at("/StatsObject/properties").has("faceit")).isTrue();
        assertThat(schemas.at("/FaceitStats/properties").has("more")).isTrue();
    }

    /**
     * The version 3 answer of {@code /api/me/game} is documented next to the version 2 one (springdoc merges the two
     * versions of a path into one operation): an anyOf, with the enrichment objects and the version 3 parameters.
     */
    @Test
    void docs_describeTheVersion3AnswerOfTheMemberGame() throws Exception {
        JsonNode docs = docs();

        JsonNode schema = docs.at("/paths/~1api~1me~1game/get/responses/200/content/application~1json/schema");
        assertThat(schema.has("oneOf")).as("a v3 payload also fits the v2 schema: anyOf, not oneOf").isFalse();
        assertThat(docs.at("/paths/~1api~1me~1game/get/responses/200/content/text~1plain/schema/type").asString())
                .isEqualTo("string");
        List<String> alternatives = new ArrayList<>();
        schema.get("anyOf").forEach(alternative -> alternatives.add(alternative.get("$ref").asString()));
        assertThat(alternatives).containsExactly("#/components/schemas/GameInfoResponse",
                "#/components/schemas/GameInfoV3Response");
        JsonNode v3 = docs.at("/components/schemas/GameInfoV3Response/properties");
        assertThat(v3.propertyNames()).contains("inGame", "igdb", "hltb", "protondb", "steamgriddb", "steamDeck",
                "pcgamingwiki", "prices", "opencritic", "twitch", "speedrun", "stats", "trailer");
        assertThat(docs.at("/components/schemas/GameInfoResponse/properties").propertyNames())
                .doesNotContain("hltb", "stats");
        // the objects it nests are components of their own, every reference resolves
        assertThat(v3.at("/hltb/$ref").asString()).isEqualTo("#/components/schemas/HltbObject");
        assertThat(docs.at("/components/schemas").has("HltbObject")).isTrue();
        assertThat(docs.at("/components/schemas").has("StatsObject")).isTrue();
        List<String> parameters = new ArrayList<>();
        docs.at("/paths/~1api~1me~1game/get/parameters").forEach(p -> parameters.add(p.get("name").asString()));
        assertThat(parameters).contains("country", "language", "lang", "f", ApiV2.VERSION_HEADER);
        for (JsonNode parameter : docs.at("/paths/~1api~1me~1game/get/parameters")) {
            if ("language".equals(parameter.get("name").asString())) {
                assertThat(parameter.at("/schema/type").asString()).isEqualTo("string");
            }
        }
    }

    /** The version 2 and summary operations keep their documentation. */
    @Test
    void docs_keepTheVersion2MemberGameDocumentation() throws Exception {
        JsonNode docs = docs();

        assertThat(docs.at("/paths/~1api~1me~1game/get/summary").asString()).contains("fully aggregated");
        assertThat(docs.at("/paths/~1api~1me~1game/get/operationId").asString()).isEqualTo("gameInfo");
        assertThat(docs.at("/paths/~1api~1me~1game~1summary/get/summary").asString()).contains("compact summary");
        assertThat(docs.at("/paths/~1api~1me~1game/get/responses/200/description").asString())
                .contains("GameInfoResponse for version 2");
    }

    private static JsonNode versionHeader(JsonNode docs, String parametersPointer) {
        for (JsonNode parameter : docs.at(parametersPointer)) {
            if (ApiV2.VERSION_HEADER.equals(parameter.get("name").asString())) {
                return parameter;
            }
        }
        throw new AssertionError("no " + ApiV2.VERSION_HEADER + " in " + parametersPointer);
    }

    @Test
    void docs_keepTheMoreLinkSchemasFreeOfTheirLinkBuilder() throws Exception {
        JsonNode schemas = docs().at("/components/schemas");

        assertThat(schemas.propertyNames()).doesNotContain("MoreLinks", "Kind");
        for (String name : List.of("IgdbRef", "ParentApp", "Demo", "FullGame", "Dlc")) {
            assertThat(schemas.get(name).get("properties").propertyNames()).as(name)
                    .contains("more").doesNotContain("links", "baseUrl", "kind", "id");
        }
    }

    @Test
    void docs_requireTheActivatedBindingsIdOrName() throws Exception {
        JsonNode required = docs().at("/components/schemas/ActivateRequest/required");

        List<String> names = new ArrayList<>();
        required.forEach(name -> names.add(name.asString()));
        assertThat(names).containsExactly("idOrName");
    }
}
