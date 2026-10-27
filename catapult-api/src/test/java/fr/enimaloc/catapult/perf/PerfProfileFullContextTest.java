package fr.enimaloc.catapult.perf;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.MockDtddApiClient;
import fr.enimaloc.catapult.getter.steam.MockSteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.notification.NotificationRecipientRepository;
import fr.enimaloc.catapult.security.JwtService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.igdb.MockIgdbClient;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryService;
import fr.enimaloc.catapult.service.steam.MockSteamStoreService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.twitch.EventSubService;
import fr.enimaloc.catapult.service.twitch.MockTwitchCategoryService;
import fr.enimaloc.catapult.service.twitch.MockTwitchChatService;
import fr.enimaloc.catapult.service.twitch.MockTwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.MockTwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.xbox.MockXboxStoreService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code perf} profile on Postgres: its seeded accounts, its test login (whose JWT opens the API like a real
 * one), its simulated game change, and fakes in place of every outbound client. The calls go out as a remote client
 * ({@code X-Forwarded-For}) so that the loopback's own access does not open the routes the token must open.
 */
@Import(FullContextContainers.class)
@ActiveProfiles("perf")
// app.igdb.client-id: the perf profile's, which full-context.properties blanks
@TestPropertySource(properties = {"catapult.perf.enabled=true", "catapult.perf.accounts=3", "app.igdb.client-id=perf"})
class PerfProfileFullContextTest extends FullContextTestBase {

    private static final String REMOTE_CLIENT = "203.0.113.7";

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    PerfDataSeeder seeder;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    GameBindingRepository bindings;

    @Autowired
    NotificationRecipientRepository notifications;

    @Autowired
    ChatCommandDefinitionRepository chatCommands;

    @Autowired
    NotificationDeliveryService deliveries;

    @Autowired
    WidgetTokenService widgetTokens;

    @Autowired
    JwtService jwtService;

    @Test
    void sentryEnvironmentIsPerf() {
        assertThat(environment.getProperty("sentry.environment")).isEqualTo("perf");
    }

    @Test
    void seedsTheAccountsOnce() throws Exception {
        assertSeeded();
        seeder.run(null);
        assertSeeded();
    }

    private void assertSeeded() {
        List<UserAccount> streamers = accounts.findAll().stream()
                .filter(a -> a.getTwitchUsername().startsWith("perf-streamer-"))
                .toList();
        assertThat(streamers).extracting(UserAccount::getTwitchUsername)
                .containsExactlyInAnyOrder("perf-streamer-001", "perf-streamer-002", "perf-streamer-003");
        assertThat(accounts.findByTwitchUsername("perf-admin")).isPresent();
        for (UserAccount streamer : streamers) {
            // a test may have added a game of its own
            assertThat(bindings.findByUser(streamer))
                    .filteredOn(b -> PerfDataSeeder.game(b.getSourceName()).isPresent())
                    .hasSize(PerfDataSeeder.GAMES.size());
            assertThat(deliveries.channels(streamer.getId())).hasSize(2);
            assertThat(notifications.countByUserIdAndReadAtIsNull(streamer.getId())).isEqualTo(5);
            assertThat(streamer.getWidgetToken()).isNotNull();
            assertThat(chatCommands.findByUser(streamer))
                    .filteredOn(c -> c.getName().equals(PerfDataSeeder.COMMAND_NAME))
                    .singleElement().satisfies(c -> assertThat(c.isEnabled()).isTrue());
        }
    }

    @Test
    void loginTokenOpensTheApi() throws Exception {
        String token = login("perf-streamer-001");

        HttpResponse<String> me = send("GET", "/api/internal/auth/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).get("username").asString()).isEqualTo("perf-streamer-001");
        assertThat(send("GET", "/api/internal/auth/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    void loginTokenExpires() throws Exception {
        Date expiration = jwtService.validate(login("perf-streamer-001")).getExpiration();

        assertThat(expiration).isNotNull()
                .isBefore(new Date(System.currentTimeMillis() + PerfService.TOKEN_VALIDITY.toMillis() + 60_000));
    }

    @Test
    void adminLoginIsAdmin() throws Exception {
        assertThat(send("GET", "/api/internal/admin/members", login("perf-admin")).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/internal/admin/members", login("perf-streamer-001")).statusCode())
                .isEqualTo(403);
    }

    @Test
    void unknownAccountIs404() throws Exception {
        assertThat(send("POST", "/api/internal/perf/login?username=nobody", null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/internal/perf/users/nobody/game?name=Hades", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void accountsListsWidgetTokens() throws Exception {
        HttpResponse<String> response = send("GET", "/api/internal/perf/accounts", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode list = json.readTree(response.body());
        assertThat(list.size()).isEqualTo(4);
        for (JsonNode account : list) {
            UUID userId = UUID.fromString(account.get("userId").asString());
            UUID widgetToken = UUID.fromString(account.get("widgetToken").asString());
            assertThat(widgetTokens.resolve(widgetToken)).map(UserAccount::getId).contains(userId);
            assertThat(account.get("username").asString()).startsWith("perf-");
        }
    }

    @Test
    void igdbKnowsThePerfGames_fromItsCacheAlone() {
        IgdbService igdb = context.getBean(IgdbService.class);

        for (PerfDataSeeder.PerfGame game : PerfDataSeeder.GAMES) {
            assertThat(igdb.findBySteamAppId(game.steamAppId())).contains(new IgdbService.IgdbGame(game.igdbId(),
                    game.name()));
        }
        // a game it has no entry for: no IGDB to ask, nor a token to ask it with
        assertThat(igdb.findBySteamAppId("1")).isEmpty();
        assertThat(igdb.getAppToken()).isEmpty();
    }

    @Test
    void gameChangeReachesTheCategoryChain() throws Exception {
        UserAccount streamer = accounts.findByTwitchUsername("perf-streamer-002").orElseThrow();

        HttpResponse<String> response = send("POST", "/api/internal/perf/users/perf-streamer-002/game?name=Hades", null);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(context.getBean(MockTwitchService.class).lastCategoryName(streamer.getId())).contains("Hades");
    }

    @Test
    void unknownGameGetsANewBinding() throws Exception {
        UserAccount streamer = accounts.findByTwitchUsername("perf-streamer-003").orElseThrow();

        assertThat(send("POST", "/api/internal/perf/users/perf-streamer-003/game?name=Brand%20New", null).statusCode())
                .isEqualTo(204);

        assertThat(bindings.findByUser(streamer)).extracting(GameBinding::getSourceName).contains("Brand New");
        assertThat(send("POST", "/api/internal/perf/users/perf-admin/game?name=Hades", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void noOutboundClientIsReal() {
        assertThat(context.getBean(TwitchService.class)).isInstanceOf(MockTwitchService.class);
        assertThat(context.getBean(TwitchCategoryService.class)).isInstanceOf(MockTwitchCategoryService.class);
        assertThat(context.getBean(TwitchChatService.class)).isInstanceOf(MockTwitchChatService.class);
        assertThat(context.getBean(EventSubService.class)).isInstanceOf(MockTwitchEventSubService.class);
        assertThat(context.getBean(IgdbClient.class)).isInstanceOf(MockIgdbClient.class);
        assertThat(context.getBean(SteamApiClient.class)).isInstanceOf(MockSteamApiClient.class);
        assertThat(context.getBean(SteamStoreService.class)).isInstanceOf(MockSteamStoreService.class);
        assertThat(context.getBean(XboxStoreService.class)).isInstanceOf(MockXboxStoreService.class);
        assertThat(context.getBean(DtddApiClient.class)).isInstanceOf(MockDtddApiClient.class);
        assertThat(environment.getProperty("xbox.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("minecraft.enabled")).isEqualTo("false");
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = send("POST", "/api/internal/perf/login?username=" + username, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asString();
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("X-Forwarded-For", REMOTE_CLIENT);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
