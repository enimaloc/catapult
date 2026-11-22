package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.repository.riot.RiotApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champions;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LeagueEntry;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LiveGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpRiotClientTest {

    static final String KEY = "RGAPI-12345678-abcd-4ef0-9abc-0123456789ab";
    static final String OTHER = "RGAPI-87654321-abcd-4ef0-9abc-0123456789ab";
    static final String PUUID = "Wb3JmG1dl8bE5P3zoUeVJmMSaMu3LvrHGwI6Gq3HpXXKSL6nXqQmZ0hHoWzMjz9aEdVq6Z1GqHJ3bA";
    static final String EUW = "https://euw1.api.riotgames.com";
    static final String EUROPE = "https://europe.api.riotgames.com";

    private MockRestServiceServer server;
    private RiotApiKeyRepository repository;
    private RiotApiKeyRotator rotator;
    private RiotRateLimiter limiter;
    private final List<String> buckets = new ArrayList<>();
    private HttpRiotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        repository = mock(RiotApiKeyRepository.class);
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry(KEY, null)));
        rotator = new RiotApiKeyRotator(repository, new SimpleMeterRegistry());
        limiter = mock(RiotRateLimiter.class);
        when(limiter.acquire(anyString())).thenAnswer(inv -> buckets.add(inv.getArgument(0)));
        client = new HttpRiotClient(builder.build(), rotator, limiter);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/riot/" + name), StandardCharsets.UTF_8);
    }

    private void json(String url, String fixture) throws IOException {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET)).andExpect(header("X-Riot-Token", KEY))
                .andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_JSON));
    }

    @Test
    void accountByRiotId_readsThePuuid_fromTheAccountCluster_withTheKeyInTheHeaderOnly() throws IOException {
        json(EUROPE + "/riot/account/v1/accounts/by-riot-id/Catapult/EUW", "account.json");

        var account = client.accountByRiotId(Cluster.EUROPE, "Catapult", "EUW");

        assertThat(account.puuid()).isEqualTo(PUUID);
        assertThat(account.gameName()).isEqualTo("Catapult");
        assertThat(account.tagLine()).isEqualTo("EUW");
        assertThat(buckets).hasSize(1).allMatch(b -> b.endsWith("@https://europe.api.riotgames.com"))
                .noneMatch(b -> b.contains(KEY));
        server.verify();
    }

    @Test
    void theSeaPlatforms_askAsiaForTheirAccount() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://asia.api.riotgames.com/riot/account/")))
                .andRespond(withSuccess(fixture("account.json"), MediaType.APPLICATION_JSON));

        client.accountByRiotId(Cluster.SEA, "Catapult", "OCE");

        server.verify();
    }

    @Test
    void aSpaceInTheName_isEncodedInThePath() throws IOException {
        server.expect(requestTo(EUROPE + "/riot/account/v1/accounts/by-riot-id/Le%20Chat/1234"))
                .andRespond(withSuccess(fixture("account.json"), MediaType.APPLICATION_JSON));

        client.accountByRiotId(Cluster.EUROPE, "Le Chat", "1234");

        server.verify();
    }

    @Test
    void riotsJson404_isNotFound_anyOther404IsAnError() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUROPE)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("account-404.json")));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUROPE)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("<html>nope</html>"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUROPE)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThatThrownBy(() -> client.accountByRiotId(Cluster.EUROPE, "x", "y"))
                .isInstanceOf(RiotNotFoundException.class);
        assertThatThrownBy(() -> client.accountByRiotId(Cluster.EUROPE, "x", "y"))
                .isInstanceOf(RiotUnavailableException.class);
        assertThatThrownBy(() -> client.accountByRiotId(Cluster.EUROPE, "x", "y"))
                .isInstanceOf(RiotUnavailableException.class);
    }

    @Test
    void anUnreadableOrWrongShaped2xx_isAnError() {
        for (String body : List.of("{}", "[]", "<html></html>", "{\"gameName\":\"x\"}", "")) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUROPE)))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.accountByRiotId(Cluster.EUROPE, "x", "y"))
                    .isInstanceOf(RiotUnavailableException.class);
        }
    }

    @Test
    void aRedirectIsNeverFollowed() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW)))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example/steal"));

        assertThatThrownBy(() -> client.lolSummoner(RiotPlatform.EUW1, PUUID))
                .isInstanceOf(RiotUnavailableException.class);
        server.verify();
    }

    @Test
    void theTransport_followsNoRedirect() {
        assertThat(HttpRiotClient.httpClient().followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }

    @Test
    void aRefusedKey_isSetAside_andTheCallTriedOnceWithAnother() throws IOException {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry(KEY, null),
                new RiotApiKeyEntry(OTHER, null)));
        rotator.refreshKeys();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("unauthorized.json")));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW)))
                .andRespond(withSuccess(fixture("lol-summoner.json"), MediaType.APPLICATION_JSON));

        assertThat(client.lolSummoner(RiotPlatform.EUW1, PUUID).level()).isEqualTo(312);

        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        assertThat(buckets).hasSize(2);
        server.verify();
    }

    @Test
    void theOnlyKeyRefused_isAnOutage() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.lolSummoner(RiotPlatform.EUW1, PUUID))
                .isInstanceOf(RiotUnavailableException.class).hasMessageNotContaining(KEY);
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        server.verify();
    }

    @Test
    void noKeyForTheCluster_isAnOutage_withoutAnyRequest() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry(KEY, "americas")));
        rotator.refreshKeys();

        assertThatThrownBy(() -> client.lolSummoner(RiotPlatform.EUW1, PUUID))
                .isInstanceOf(RiotUnavailableException.class);
        assertThat(buckets).isEmpty();
    }

    @Test
    void a429_pausesTheBucket_cappingRetryAfter_andSetsTheKeyAsideForAnApplicationLimit() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "99999").header("X-Rate-Limit-Type", "application"));

        assertThatThrownBy(() -> client.lolSummoner(RiotPlatform.EUW1, PUUID))
                .isInstanceOf(RiotUnavailableException.class);

        verify(limiter).pause(startsWith(""), eq(Duration.ofMinutes(10)));
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
    }

    @Test
    void aMethodOrService429_pausesOnlyTheBucket() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-Rate-Limit-Type", "method"));

        assertThatThrownBy(() -> client.lolSummoner(RiotPlatform.EUW1, PUUID))
                .isInstanceOf(RiotUnavailableException.class);

        verify(limiter).pause(anyString(), eq(Duration.ofSeconds(60)));
        assertThat(rotator.blockedKeyCount()).isZero();
    }

    @Test
    void theAppRateLimitHeader_isHandedToTheLimiter() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW)))
                .andRespond(withSuccess(fixture("lol-summoner.json"), MediaType.APPLICATION_JSON)
                        .header("X-App-Rate-Limit", "500:10,30000:600"));

        client.lolSummoner(RiotPlatform.EUW1, PUUID);

        verify(limiter).learn(anyString(), eq("500:10,30000:600"));
    }

    @Test
    void aRefusingLimiter_sendsNothing() {
        when(limiter.acquire(anyString())).thenReturn(false);

        assertThatThrownBy(() -> client.lolSummoner(RiotPlatform.EUW1, PUUID))
                .isInstanceOf(RiotUnavailableException.class);
        server.verify();
    }

    @Test
    void lolLeague_readsTheQueues_andEmptyIsUnranked() throws IOException {
        json(EUW + "/lol/league/v4/entries/by-puuid/" + PUUID, "lol-league.json");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withSuccess("[]",
                MediaType.APPLICATION_JSON));

        List<LeagueEntry> entries = client.lolLeague(RiotPlatform.EUW1, PUUID);

        assertThat(entries).containsExactly(new LeagueEntry("RANKED_SOLO_5x5", "GOLD", "II", 57, 61, 54),
                new LeagueEntry("RANKED_FLEX_SR", "SILVER", "I", 12, 10, 9));
        assertThat(client.lolLeague(RiotPlatform.EUW1, PUUID)).isEmpty();
    }

    @Test
    void aLeagueThatIsNotAList_isAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withSuccess("{\"a\":1}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withSuccess("[{\"tier\":\"GOLD\"}]",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.lolLeague(RiotPlatform.EUW1, PUUID)).isInstanceOf(RiotUnavailableException.class);
        assertThatThrownBy(() -> client.tftLeague(RiotPlatform.EUW1, PUUID)).isInstanceOf(RiotUnavailableException.class);
    }

    @Test
    void tftLeague_readsTheQueue() throws IOException {
        json(EUW + "/tft/league/v1/by-puuid/" + PUUID, "tft-league.json");

        assertThat(client.tftLeague(RiotPlatform.EUW1, PUUID))
                .containsExactly(new LeagueEntry("RANKED_TFT", "PLATINUM", "IV", 3, 22, 71));
    }

    @Test
    void theActiveGame_givesTheMembersChampion_andRiots404IsNoGame() throws IOException {
        json(EUW + "/lol/spectator/v5/active-games/by-summoner/" + PUUID, "lol-active-game.json");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EUW))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("lol-active-game-404.json")));

        LiveGame game = client.lolActiveGame(RiotPlatform.EUW1, PUUID).orElseThrow();

        assertThat(game.championId()).isEqualTo(266);
        assertThat(game.gameMode()).isEqualTo("CLASSIC");
        assertThat(game.queueId()).isEqualTo(420);
        assertThat(game.startedAt()).isEqualTo(Instant.ofEpochMilli(1763890000000L));
        assertThat(game.lengthSeconds()).isEqualTo(754L);
        assertThat(client.lolActiveGame(RiotPlatform.EUW1, PUUID)).isEmpty();
    }

    @Test
    void dataDragon_isAskedWithoutAKey() throws IOException {
        server.expect(requestTo(HttpRiotClient.DDRAGON + "/api/versions.json")).andExpect(headerDoesNotExist("X-Riot-Token"))
                .andRespond(withSuccess(fixture("ddragon-versions.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HttpRiotClient.DDRAGON + "/cdn/16.23.1/data/fr_FR/champion.json"))
                .andExpect(headerDoesNotExist("X-Riot-Token"))
                .andRespond(withSuccess(fixture("ddragon-champions.json"), MediaType.APPLICATION_JSON));

        assertThat(client.latestVersion()).isEqualTo("16.23.1");
        Champions champions = client.champions("16.23.1", "fr_FR");

        assertThat(champions.byKey().get("266").name()).isEqualTo("Aatrox");
        assertThat(champions.byKey().get("62").id()).isEqualTo("MonkeyKing");
        assertThat(buckets).containsOnly("ddragon");
        assertThat(HttpRiotClient.championIconUrl("16.23.1", "Aatrox"))
                .isEqualTo("https://ddragon.leagueoflegends.com/cdn/16.23.1/img/champion/Aatrox.png");
    }

    @Test
    void dataDragon_wrongShapes_areErrors_andBadArgumentsAreRefused() {
        for (String body : List.of("[]", "[\"../x\"]", "{}")) {
            server.expect(requestTo(HttpRiotClient.DDRAGON + "/api/versions.json"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.latestVersion()).isInstanceOf(RiotUnavailableException.class);
        }
        assertThatThrownBy(() -> client.champions("../../x", "fr_FR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.champions("16.23.1", "fr")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTooBigAnswer_isAnError() throws IOException {
        assertThat(HttpRiotClient.readLimited(new ByteArrayInputStream(new byte[11]), 10)).isNull();
        assertThat(HttpRiotClient.readLimited(new ByteArrayInputStream(new byte[10]), 10)).hasSize(10);
    }

    @Test
    void retryAfter_isBoundedAndDefaulted() {
        assertThat(HttpRiotClient.retryAfter(null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpRiotClient.retryAfter("x")).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpRiotClient.retryAfter("0")).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpRiotClient.retryAfter("5")).isEqualTo(Duration.ofSeconds(5));
        assertThat(HttpRiotClient.retryAfter("100000")).isEqualTo(Duration.ofMinutes(10));
    }
}
