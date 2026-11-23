package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.domain.hypixel.HypixelApiKeyEntry;
import fr.enimaloc.catapult.getter.hypixel.HypixelApiKeyRotator;
import fr.enimaloc.catapult.repository.hypixel.HypixelApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
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
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpHypixelClientTest {

    static final String KEY = "12345678-abcd-4ef0-9abc-0123456789ab";
    static final String OTHER = "87654321-abcd-4ef0-9abc-0123456789ab";
    static final String UUID = "069a79f444e94726a5befca90e38aaf5";
    static final String PLAYER = "https://api.hypixel.net/v2/player?uuid=" + UUID;
    static final String STATUS = "https://api.hypixel.net/v2/status?uuid=" + UUID;

    private MockRestServiceServer server;
    private HypixelApiKeyRepository repository;
    private HypixelApiKeyRotator rotator;
    private HypixelRateLimiter limiter;
    private final List<String> buckets = new ArrayList<>();
    private HttpHypixelClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        repository = mock(HypixelApiKeyRepository.class);
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new HypixelApiKeyEntry(KEY)));
        rotator = new HypixelApiKeyRotator(repository, new SimpleMeterRegistry());
        limiter = mock(HypixelRateLimiter.class);
        when(limiter.acquire(anyString())).thenAnswer(inv -> buckets.add(inv.getArgument(0)));
        client = new HttpHypixelClient(builder.build(), rotator, limiter);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/hypixel/" + name), StandardCharsets.UTF_8);
    }

    private void keyed(String url, String fixture) throws IOException {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET)).andExpect(header("API-Key", KEY))
                .andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_JSON));
    }

    @Test
    void player_readsTheProjection_withTheKeyInTheHeaderOnly() throws IOException {
        keyed(PLAYER, "player.json");

        HypixelPlayer player = client.player(UUID);

        assertThat(player.displayName()).isEqualTo("Notch");
        assertThat(player.rank()).isEqualTo("MVP_PLUS");
        assertThat(player.networkExp()).isEqualTo(9_997_500L);
        assertThat(player.karma()).isEqualTo(1_234_567);
        assertThat(player.achievementPoints()).isEqualTo(5_420);
        assertThat(player.onlineStatusHidden()).isFalse();
        assertThat(player.lastLogin()).isEqualTo(Instant.ofEpochMilli(1795950000000L));
        assertThat(player.bedWars().stars()).isEqualTo(4);
        assertThat(player.bedWars().wins()).isEqualTo(120);
        assertThat(player.bedWars().finalKills()).isEqualTo(310);
        assertThat(player.bedWars().bedsBroken()).isEqualTo(145);
        assertThat(player.skyWars().stars()).isEqualTo(12);
        assertThat(player.skyWars().wins()).isEqualTo(64);
        assertThat(player.skyWars().kills()).isEqualTo(380);
        assertThat(buckets).hasSize(1).allMatch(b -> b.startsWith("key:")).noneMatch(b -> b.contains(KEY));
        assertThat(player.toString()).doesNotContain(KEY);
        server.verify();
    }

    @Test
    void aPlayerWithoutLastLogin_hidesTheirOnlineStatus() throws IOException {
        keyed(PLAYER, "player-hidden.json");

        HypixelPlayer player = client.player(UUID);

        assertThat(player.onlineStatusHidden()).isTrue();
        assertThat(player.lastLogin()).isNull();
    }

    @Test
    void aNewPlayer_hasNoGameStats() throws IOException {
        keyed(PLAYER, "player-new.json");

        HypixelPlayer player = client.player(UUID);

        assertThat(player.networkExp()).isZero();
        assertThat(player.bedWars()).isNull();
        assertThat(player.skyWars()).isNull();
        assertThat(player.rank()).isNull();
    }

    @Test
    void aNullPlayer_isNotFound_onlyWhenSuccessIsTrue() throws IOException {
        keyed(PLAYER, "player-null.json");
        server.expect(requestTo(PLAYER)).andRespond(withSuccess("{\"success\":false,\"player\":null}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelNotFoundException.class);
        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);
    }

    @Test
    void anUnreadableOrWrongShaped2xx_isAnError() {
        List<String> bodies = List.of("{}", "[]", "<html></html>", "", "{\"success\":true}",
                "{\"success\":true,\"player\":[]}", "{\"success\":true,\"player\":{}}",
                "{\"success\":true,\"player\":{\"uuid\":\"00000000000000000000000000000001\"}}",
                "{\"success\":\"true\",\"player\":null}");
        for (String body : bodies) {
            server.expect(requestTo(PLAYER)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < bodies.size(); i++) {
            assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);
        }
    }

    @Test
    void any404_isAnError_forHypixel() {
        server.expect(requestTo(PLAYER)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"cause\":\"Not found\"}"));

        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);
    }

    @Test
    void aRedirectIsNeverFollowed() {
        server.expect(requestTo(PLAYER))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example/steal"));

        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);
        server.verify();
    }

    @Test
    void theTransport_followsNoRedirect() {
        assertThat(HttpHypixelClient.httpClient().followRedirects())
                .isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }

    @Test
    void aRefusedKey_isSetAside_andTheCallTriedOnceWithAnother() throws IOException {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new HypixelApiKeyEntry(KEY),
                new HypixelApiKeyEntry(OTHER)));
        rotator.refreshKeys();
        server.expect(requestTo(PLAYER)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("invalid-key.json")));
        server.expect(requestTo(PLAYER)).andRespond(withSuccess(fixture("player.json"), MediaType.APPLICATION_JSON));

        assertThat(client.player(UUID).displayName()).isEqualTo("Notch");

        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        assertThat(buckets).hasSize(2).doesNotHaveDuplicates();
        server.verify();
    }

    @Test
    void theOnlyKeyRefused_isAnOutage_andNothingIsSentWhileItIsSetAside() {
        server.expect(requestTo(PLAYER)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"cause\":\"Invalid API key\"}"));

        assertThatThrownBy(() -> client.player(UUID))
                .isInstanceOf(HypixelUnavailableException.class).hasMessageNotContaining(KEY);
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        server.verify();

        buckets.clear();
        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);
        assertThat(buckets).isEmpty();
    }

    @Test
    void aForbiddenRoute_isAnOutage_withoutSettingTheKeyAside() throws IOException {
        server.expect(requestTo(STATUS)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"cause\":\"Forbidden\"}"));
        server.expect(requestTo(STATUS)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        keyed(PLAYER, "player.json");

        assertThatThrownBy(() -> client.status(UUID)).isInstanceOf(HypixelUnavailableException.class);
        assertThatThrownBy(() -> client.status(UUID)).isInstanceOf(HypixelUnavailableException.class);

        assertThat(rotator.blockedKeyCount()).isZero();
        assertThat(client.player(UUID).displayName()).isEqualTo("Notch");
        server.verify();
    }

    @Test
    void anEmptyPool_isAnOutage_withoutAnyRequest() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator.refreshKeys();

        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);
        assertThatThrownBy(() -> client.status(UUID)).isInstanceOf(HypixelUnavailableException.class);
        assertThat(buckets).isEmpty();
    }

    @Test
    void a429_pausesTheBucket_andSetsTheKeyAside_cappingRetryAfter() throws IOException {
        server.expect(requestTo(PLAYER)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "99999").contentType(MediaType.APPLICATION_JSON)
                .body(fixture("throttle.json")));

        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);

        verify(limiter).pause(org.mockito.ArgumentMatchers.startsWith("key:"), eq(Duration.ofMinutes(10)));
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
    }

    @Test
    void a429WithoutRetryAfter_usesRateLimitReset_elseAMinute() {
        server.expect(requestTo(PLAYER)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header("RateLimit-Reset", "42"));

        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);

        verify(limiter).pause(anyString(), eq(Duration.ofSeconds(42)));
        assertThat(HttpHypixelClient.pause(null, null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpHypixelClient.pause("x", "-3")).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpHypixelClient.pause("5", "42")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aSpentQuota_pausesTheKeyUntilTheReset_yetServesTheAnswer() throws IOException {
        server.expect(requestTo(PLAYER)).andRespond(withSuccess(fixture("player.json"), MediaType.APPLICATION_JSON)
                .header("RateLimit-Remaining", "0").header("RateLimit-Reset", "17"));

        assertThat(client.player(UUID).displayName()).isEqualTo("Notch");

        verify(limiter).pause(org.mockito.ArgumentMatchers.startsWith("key:"), eq(Duration.ofSeconds(17)));
    }

    @Test
    void aQuotaNotSpent_pausesNothing() throws IOException {
        server.expect(requestTo(PLAYER)).andRespond(withSuccess(fixture("player.json"), MediaType.APPLICATION_JSON)
                .header("RateLimit-Remaining", "59").header("RateLimit-Reset", "17"));

        client.player(UUID);

        verify(limiter, never()).pause(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aRefusingLimiter_sendsNothing() {
        when(limiter.acquire(anyString())).thenReturn(false);

        assertThatThrownBy(() -> client.player(UUID)).isInstanceOf(HypixelUnavailableException.class);
        server.verify();
    }

    @Test
    void aBadUuid_isRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> client.player("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.status("../v2/boosters")).isInstanceOf(IllegalArgumentException.class);
        assertThat(buckets).isEmpty();
    }

    @Test
    void status_readsTheSession() throws IOException {
        keyed(STATUS, "status.json");
        keyed(STATUS, "status-offline.json");

        var online = client.status(UUID);
        var offline = client.status(UUID);

        assertThat(online.online()).isTrue();
        assertThat(online.gameType()).isEqualTo("BEDWARS");
        assertThat(online.mode()).isEqualTo("EIGHT_TWO");
        assertThat(online.map()).isEqualTo("Lighthouse");
        assertThat(offline.online()).isFalse();
        assertThat(offline.gameType()).isNull();
    }

    @Test
    void aSessionOfTheWrongShape_isAnError() {
        for (String body : List.of("{\"success\":true}", "{\"success\":true,\"session\":{}}",
                "{\"success\":true,\"session\":{\"online\":\"yes\"}}")) {
            server.expect(requestTo(STATUS)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.status(UUID)).isInstanceOf(HypixelUnavailableException.class);
        }
    }

    @Test
    void games_areAskedWithoutAKey() throws IOException {
        server.expect(requestTo("https://api.hypixel.net/v2/resources/games")).andExpect(headerDoesNotExist("API-Key"))
                .andRespond(withSuccess(fixture("games.json"), MediaType.APPLICATION_JSON));

        var games = client.games();

        assertThat(games.names()).containsEntry("BEDWARS", "Bed Wars").containsEntry("SKYWARS", "SkyWars");
        assertThat(games.games()).filteredOn(g -> g.type().equals("QUAKECRAFT")).singleElement()
                .satisfies(g -> assertThat(g.legacy()).isTrue());
        assertThat(games.games()).filteredOn(g -> g.type().equals("SKYCLASH")).singleElement()
                .satisfies(g -> assertThat(g.retired()).isTrue());
        assertThat(buckets).containsExactly(HypixelRateLimiter.PUBLIC);
    }

    @Test
    void gamesOfTheWrongShape_areAnError() {
        for (String body : List.of("{\"success\":true,\"games\":{}}", "{\"success\":true,\"games\":[]}",
                "{\"success\":true,\"games\":{\"X\":{\"id\":1}}}", "{\"success\":false}")) {
            server.expect(requestTo("https://api.hypixel.net/v2/resources/games"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.games()).isInstanceOf(HypixelUnavailableException.class);
        }
    }

    @Test
    void mojang_givesTheProfile_withoutAKey() throws IOException {
        server.expect(requestTo("https://api.mojang.com/users/profiles/minecraft/Notch"))
                .andExpect(headerDoesNotExist("API-Key"))
                .andRespond(withSuccess(fixture("mojang-profile.json"), MediaType.APPLICATION_JSON));

        var profile = client.profileByName("Notch");

        assertThat(profile.uuid()).isEqualTo(UUID);
        assertThat(profile.name()).isEqualTo("Notch");
        assertThat(buckets).containsExactly(HypixelRateLimiter.MOJANG);
    }

    @Test
    void mojangsJson404_isNotFound_anyOtherIsAnError() throws IOException {
        server.expect(requestTo(startsWith("https://api.mojang.com/"))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("mojang-404.json")));
        server.expect(requestTo(startsWith("https://api.mojang.com/"))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML).body("<html>nope</html>"));
        server.expect(requestTo(startsWith("https://api.mojang.com/"))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{}"));
        server.expect(requestTo(startsWith("https://api.mojang.com/"))).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(startsWith("https://api.mojang.com/")))
                .andRespond(withSuccess("{\"id\":\"nope\",\"name\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.profileByName("ghost")).isInstanceOf(HypixelNotFoundException.class);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.profileByName("ghost")).isInstanceOf(HypixelUnavailableException.class);
        }
    }

    @Test
    void aMojang429_pausesTheMojangBucket() {
        server.expect(requestTo(startsWith("https://api.mojang.com/"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.profileByName("Notch")).isInstanceOf(HypixelUnavailableException.class);

        verify(limiter).pause(HypixelRateLimiter.MOJANG, Duration.ofSeconds(60));
        assertThat(rotator.blockedKeyCount()).isZero();
    }

    @Test
    void aBadName_isRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> client.profileByName("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.profileByName("ab")).isInstanceOf(IllegalArgumentException.class);
        assertThat(buckets).isEmpty();
    }

    @Test
    void aTooBigAnswer_isAnError() throws IOException {
        assertThat(HttpHypixelClient.readLimited(new ByteArrayInputStream(new byte[11]), 10)).isNull();
        assertThat(HttpHypixelClient.readLimited(new ByteArrayInputStream(new byte[10]), 10)).hasSize(10);
    }

    @Test
    void skyWarsStars_areReadFromTheFormattedLevel() {
        assertThat(HttpHypixelClient.skyWarsStars("§b12⋆")).isEqualTo(12);
        assertThat(HttpHypixelClient.skyWarsStars("§7[§f§l1§a§l5§b§l0§7⋆]")).isEqualTo(150);
        assertThat(HttpHypixelClient.skyWarsStars("§7⋆")).isNull();
        assertThat(HttpHypixelClient.skyWarsStars(null)).isNull();
    }
}
