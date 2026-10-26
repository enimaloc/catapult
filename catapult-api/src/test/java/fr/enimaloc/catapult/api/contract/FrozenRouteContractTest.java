package fr.enimaloc.catapult.api.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddTopicsCache;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameDetailsRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.TwitchatActionTokenService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The success and refusal answers of the external routes, byte for byte, on a real server: status, the headers that
 * matter and the body of each, recorded under {@code src/test/resources/frozen-routes/} before the internal routes
 * moved. Outside callers (OBS widgets, Twitchat, API token clients, pasted URLs) depend on every one of them, so
 * the snapshots are never edited: a case that fails means an external route changed.
 *
 * <p>Recording is {@code FROZEN_ROUTES_RECORD=1 ./gradlew :catapult-api:test --tests '*FrozenRouteContractTest'}.
 * Third parties are mocks with one fixed answer each, so the bodies do not depend on the network.
 */
@Import(FullContextContainers.class)
@TestPropertySource(properties = "dtdd.enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrozenRouteContractTest extends FullContextTestBase {

    private static final String IGDB_ID = "777";
    private static final String STEAM_APP_ID = "480";
    private static final String XBOX_PRODUCT_ID = "9NBLGGH2JHXJ";
    private static final long DTDD_ID = 16758L;
    private static final String OBS_PASSWORD = "obs-secret";

    @MockitoBean
    TwitchService twitchService;

    @MockitoBean
    IgdbService igdbService;

    @MockitoBean
    SteamStoreService steamStoreService;

    @MockitoBean
    XboxStoreService xboxStoreService;

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    WidgetTokenService widgetTokenService;

    @Autowired
    UserApiTokenService userApiTokenService;

    @Autowired
    CustomBindingService customBindingService;

    @Autowired
    ExperimentRepository experiments;

    @Autowired
    ExperimentAssignmentRepository assignments;

    @Autowired
    GameBindingRepository gameBindings;

    @Autowired
    GameStateService gameStateService;

    @Autowired
    TwitchatWidgetSettingsRepository widgetSettings;

    @Autowired
    TokenEncryptionService encryption;

    @Autowired
    TwitchatActionTokenService actionTokens;

    @Autowired
    DtddGameCacheRepository dtddGames;

    @Autowired
    DtddTopicsCacheRepository dtddTopics;

    @Autowired
    IgdbGameDetailsRepository igdbDetails;

    @Autowired
    TransactionOperations transactions;

    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    UUID widget;
    String personalToken;
    UUID bindingId;
    UUID firstAction;
    UUID secondAction;

    @BeforeAll
    void fixture() {
        // one transaction: the experiment's variants are lazy
        transactions.executeWithoutResult(status -> fixtureRows());
    }

    private void fixtureRows() {
        UserAccount account = account("frozen-routes", "frozen-routes-twitch");
        widget = widgetTokenService.getOrCreate(account);
        personalToken = userApiTokenService.regenerate(account).token();
        openCustomBindingsTo(account);
        customBindingService.create(
                account, new CustomBindingRequest("Art", "509660", "Art", Set.of(), Set.of("Flashing"), false));

        // the last known game: a MANUAL binding, its store page being the IGDB entry below
        GameBinding binding = new GameBinding();
        binding.setUser(account);
        binding.setSourceType(GameBinding.SourceType.MANUAL);
        binding.setSourceId("frozen-game");
        binding.setSourceName("Frozen Game");
        binding.setStatus(GameBinding.Status.MANUAL);
        binding.setTwitchGameId("12345");
        binding.setTwitchGameName("Frozen Game");
        binding.getTws().add("flashing-lights");
        binding.getCcls().add("violence");
        bindingId = gameBindings.save(binding).getId();
        gameStateService.updateState(
                account, new DetectedGame("frozen-game", GameBinding.SourceType.MANUAL, "Frozen Game"));

        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(account);
        settings.setEnabled(true);
        settings.setObsHost("localhost");
        settings.setObsPort(4455);
        settings.setObsPasswordEncrypted(encryption.encrypt(OBS_PASSWORD));
        widgetSettings.save(settings);

        // actions live on their own account: enabling the bot there is a no-op that cannot disturb the main one
        UserAccount actor = account("frozen-routes-actions", "frozen-routes-actions-twitch");
        firstAction = actionTokens.generate(actor.getId(), TwitchatActionType.ENABLE_BOT, Map.of());
        secondAction = actionTokens.generate(actor.getId(), TwitchatActionType.ENABLE_BOT, Map.of());

        dtddGames.save(new DtddGameCache(
                DTDD_ID, "Frozen Game", "frozen-game", "videogame", "https://example.test/poster.jpg", Instant.now()));
        dtddTopics.save(new DtddTopicsCache(
                DTDD_ID, List.of("a dog dies"), List.of("a cat dies"), List.of("jump scares"), Instant.now()));
        igdbDetails.save(igdbDetails());
    }

    /** Mocks are reset after each test, so the third parties answer again before each. */
    @BeforeEach
    void thirdParties() throws Exception {
        when(twitchService.updateChannelReporting(any(), any())).thenReturn(ChannelUpdateOutcome.APPLIED);
        when(igdbService.findByName(any())).thenReturn(Optional.of(new IgdbService.IgdbGame(IGDB_ID, "Frozen Game")));
        SteamStoreService.SteamStorePage page = JsonMapper.builder()
                .build()
                .readValue(
                        "{\"type\":\"game\",\"name\":\"Spacewar\",\"steam_appid\":480,\"required_age\":0,"
                                + "\"short_description\":\"A test app\",\"is_free\":true}",
                        SteamStoreService.SteamStorePage.class);
        when(steamStoreService.fetchData(eq(STEAM_APP_ID), any(), anyBoolean())).thenReturn(Optional.of(page));
        when(xboxStoreService.fetchProduct(eq(XBOX_PRODUCT_ID), any()))
                .thenReturn(Optional.of(new XboxStoreService.XboxProduct(
                        "Frozen Product",
                        "A test product",
                        "Frozen Publisher",
                        "Frozen Developer",
                        null,
                        null,
                        null,
                        null,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        List.of(new XboxStoreService.XboxImage("https://example.test/box.png", "boxart", 10, 10, null)),
                        List.of(),
                        List.of(),
                        List.of(),
                        "https://www.xbox.com/games/store/frozen/" + XBOX_PRODUCT_ID)));
    }

    /** One case: its snapshot name, the request, and the members of the body that change from run to run. */
    record Case(String name, String method, String path, Map<String, String> headers, String... volatilePointers) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.put(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    private static Case c(String name, String method, String path, String... volatilePointers) {
        return new Case(name, method, path, Map.of(), volatilePointers);
    }

    /** In this order: the custom binding is activated then deactivated, so later cases see the earlier ones' effect. */
    Stream<Case> answers() {
        String t = widget.toString();
        Map<String, String> bearer = headers("Authorization", "Bearer " + personalToken);
        return Stream.of(
                c("v1-game", "GET", "/api/game/" + t),
                c("v1-game-lang-fr", "GET", "/api/game/" + t + "?lang=fr"),
                new Case("v1-game-cors", "GET", "/api/game/" + t, headers("Origin", "https://obs.example")),
                // the binding's generated id (also in its "more" link) and creation time
                // as catapult-web's relay sends them: the links name the public origin
                new Case(
                        "v2-game",
                        "GET",
                        "/api/user/game/" + t,
                        headers(
                                "X-Forwarded-Proto", "https",
                                "X-Forwarded-Host", "catapult.example",
                                "X-Forwarded-Port", "443"),
                        "/catapult/bindingId", "/catapult/createdAt", "/catapult/more"),
                // as catapult-web's relay sends them: the links name the public origin
                new Case(
                        "v2-game-version-2",
                        "GET",
                        "/api/user/game/" + t,
                        headers(
                                "X-API-Version", "2",
                                "X-Forwarded-Proto", "https",
                                "X-Forwarded-Host", "catapult.example",
                                "X-Forwarded-Port", "443"),
                        "/catapult/bindingId", "/catapult/createdAt", "/catapult/more"),
                c("v2-summary", "GET", "/api/user/summary/" + t),
                // creation and update time of the binding
                c("v2-catapult", "GET", "/api/user/catapult/" + bindingId, "/createdAt", "/updatedAt"),
                c("v2-dtdd", "GET", "/api/user/dtdd/" + DTDD_ID),
                c("v2-igdb", "GET", "/api/user/igdb/" + IGDB_ID),
                c("v2-steam", "GET", "/api/user/steam/" + STEAM_APP_ID),
                c("v2-xbox", "GET", "/api/user/xbox/" + XBOX_PRODUCT_ID),
                // the generated id of the binding
                new Case("custom-bindings-list", "GET", "/api/v2/custom-bindings", bearer, "/bindings/0/id"),
                new Case("custom-bindings-activate", "POST", "/api/v2/custom-bindings/Art/activate", bearer),
                new Case("custom-bindings-activate-unknown", "POST", "/api/v2/custom-bindings/nope/activate", bearer),
                new Case("custom-bindings-deactivate", "POST", "/api/v2/custom-bindings/deactivate", bearer),
                new Case(
                        "custom-bindings-preflight",
                        "OPTIONS",
                        "/api/v2/custom-bindings/Art/activate",
                        headers("Origin", "https://twitchat.fr", "Access-Control-Request-Method", "POST")),
                c("twitchat-widget", "GET", "/api/twitchat/widget/" + t),
                // the generated id of the widget's owner
                c("twitchat-widget-access", "GET", "/api/twitchat/widget/" + t + "/access", "/ownerId"),
                c("twitchat-widget-flush", "POST", "/api/twitchat/widget/" + t + "/notifications/flush"),
                c("twitchat-action", "POST", "/api/twitchat/actions/" + firstAction),
                c("twitchat-action-again", "POST", "/api/twitchat/actions/" + firstAction),
                new Case(
                        "twitchat-action-bearer",
                        "POST",
                        "/api/twitchat/actions/" + secondAction,
                        headers("Authorization", "Bearer sent-by-twitchat")),
                c("steam-callback-unknown-nonce", "GET", "/api/connect/steam/callback?nonce=unknown"),
                // the servers name the host the request came to
                c("api-docs", "GET", "/api/v3/api-docs", "/servers"),
                // the OAuth redirect URL carries this run's port
                c("api-docs-swagger-config", "GET", "/api/v3/api-docs/swagger-config", "/oauth2RedirectUrl"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answers")
    void answersAsRecorded(Case route) throws Exception {
        FrozenSnapshot.assertMatches(
                route.name(), send(route.method(), route.path(), route.headers()), route.volatilePointers());
    }

    @Test
    void trailingSlashAnswersAsToday() throws Exception {
        // Boot's error page: its timestamp, and the path with this run's widget token
        FrozenSnapshot.assertMatches(
                "trailing-slash-v1-game", send("GET", "/api/game/" + widget + "/", Map.of()), "/timestamp", "/path");
        FrozenSnapshot.assertMatches(
                "trailing-slash-v2-game",
                send("GET", "/api/user/game/" + widget + "/", Map.of()),
                "/timestamp",
                "/path");
    }

    @Test
    void versionHeaderAnswersAsToday() throws Exception {
        // Boot's error page: its timestamp, and the path with this run's widget token
        FrozenSnapshot.assertMatches(
                "v2-game-version-1",
                send("GET", "/api/user/game/" + widget, headers("X-API-Version", "1")),
                "/timestamp",
                "/path");
        FrozenSnapshot.assertMatches(
                "v2-game-version-3",
                send("GET", "/api/user/game/" + widget, headers("X-API-Version", "3")),
                "/timestamp",
                "/path");
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UserAccount account(String username, String twitchId) {
        UserAccount account = new UserAccount();
        account.setTwitchUsername(username);
        account.setTwitchId(twitchId);
        account.setStatus(UserAccount.Status.ACTIVE);
        return accounts.save(account);
    }

    /** The experiment gate opened to the account, as the access matrix does. */
    private void openCustomBindingsTo(UserAccount account) {
        Experiment experiment = experiments
                .findByKey(CustomBindingService.EXPERIMENT_KEY)
                .orElseThrow(() -> new IllegalStateException("custom bindings experiment not seeded"));
        experiment.setStatus(Experiment.Status.ACTIVE);
        experiments.save(experiment);
        ExperimentVariant enabled = experiment.getVariants().stream()
                .filter(v -> "enabled".equals(v.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("custom bindings experiment has no enabled variant"));
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(enabled);
        assignment.setUser(account);
        assignments.save(assignment);
    }

    private static IgdbGameDetails igdbDetails() {
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId(IGDB_ID);
        details.setSlug("frozen-game");
        details.setSummary("A game kept for the contract tests");
        details.setFirstReleaseDate(Instant.parse("2020-01-01T00:00:00Z"));
        details.setFetchedAt(Instant.now());
        details.setRating(80.5);
        details.setPlatforms(List.of("PC (Microsoft Windows)"));
        details.setGenres(List.of("Adventure"));
        return details;
    }
}
