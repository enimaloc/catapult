package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpBungieClientTest {

    private static final String API_KEY = "0123456789abcdef0123456789abcdef";
    private static final String SECRET = "client-secret-value";
    private static final String PROFILE = "https://www.bungie.net/Platform/Destiny2/3/Profile/4611686018467284386/";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private MockRestServiceServer server;
    private BungieCredentialsService credentials;
    private BungieRateLimiter limiter;
    private final AtomicInteger acquired = new AtomicInteger();
    private HttpBungieClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        credentials = mock(BungieCredentialsService.class);
        when(credentials.current()).thenReturn(Optional.of(
                new BungieCredentialsService.Credentials("12345", SECRET, API_KEY)));
        limiter = mock(BungieRateLimiter.class);
        when(limiter.budget()).thenReturn((FetchBudget) () -> {
            acquired.incrementAndGet();
            return true;
        });
        client = new HttpBungieClient(builder.build(), credentials, limiter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/bungie/" + name), StandardCharsets.UTF_8);
    }

    private void answer(String url, HttpStatus status, String fixture) throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(url))).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(fixture(fixture)));
    }

    // --- OAuth ---

    @Test
    void refresh_sendsTheSecretInBasic_andTheTokenInTheBody_neverInTheUrl() throws IOException {
        String basic = Base64.getEncoder().encodeToString(("12345:" + SECRET).getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://www.bungie.net/platform/app/oauth/token/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(headerDoesNotExist("X-API-Key"))
                .andExpect(content().string("grant_type=refresh_token&refresh_token=old%2Brefresh"))
                .andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));

        BungieData.TokenGrant grant = client.refresh("old+refresh");

        assertThat(grant.accessToken()).isEqualTo("CNbbAhKGAgAg-access");
        assertThat(grant.refreshToken()).isEqualTo("CNbbAhKGAgAg-refresh");
        assertThat(grant.membershipId()).isEqualTo("14982373");
        assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(grant.refreshExpiresAt()).isEqualTo(NOW.plusSeconds(7_776_000));
        assertThat(grant.toString()).doesNotContain("CNbbAhKGAgAg");
        assertThat(acquired).hasValue(1);
        server.verify();
    }

    @Test
    void refresh_invalidGrant_isRefused_butAnyOtherFailureIsAnOutage() throws IOException {
        server.expect(requestTo("https://www.bungie.net/platform/app/oauth/token/"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("token-invalid-grant.json")));
        server.expect(requestTo("https://www.bungie.net/platform/app/oauth/token/"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));
        server.expect(requestTo("https://www.bungie.net/platform/app/oauth/token/"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.bungie.net/platform/app/oauth/token/"))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create("https://www.bungie.net/en/")));

        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(BungieExceptions.RefreshRefused.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(BungieUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(BungieUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(BungieUnavailableException.class)
                .hasMessageNotContaining("r=");
        server.verify();
    }

    @Test
    void withoutAnApplication_nothingIsSent() {
        when(credentials.current()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> client.currentSeasonHash()).isInstanceOf(BungieUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(BungieUnavailableException.class);
        assertThat(acquired).hasValue(0);
        server.verify();
    }

    @Test
    void currentUser_readsTheBungieNameAndThePrimaryMembership() throws IOException {
        server.expect(requestTo("https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer member-token"))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(withSuccess(fixture("memberships.json"), MediaType.APPLICATION_JSON));

        BungieData.CurrentUser user = client.currentUser("member-token");

        assertThat(user.membershipId()).isEqualTo("14982373");
        assertThat(user.displayName()).isEqualTo("Guardian#0042");
        assertThat(user.memberships()).hasSize(2);
        assertThat(user.destiny().membershipType()).isEqualTo(3);
        assertThat(user.destiny().membershipId()).isEqualTo("4611686018467284386");
        server.verify();
    }

    // --- Destiny 2 ---

    @Test
    void profile_readsRankCharactersAndTheActivityUnderWay() throws IOException {
        server.expect(requestTo(PROFILE + "?components=100,200,204"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t"))
                .andExpect(requestTo(not(containsString(API_KEY))))
                .andRespond(withSuccess(fixture("profile.json"), MediaType.APPLICATION_JSON));

        BungieData.Profile profile = client.profile(3, "4611686018467284386", "t");

        assertThat(profile.profilePrivate()).isFalse();
        assertThat(profile.guardianRank()).isEqualTo(8);
        assertThat(profile.lastPlayed()).isEqualTo(Instant.parse("2026-11-22T21:15:03Z"));
        assertThat(profile.currentSeasonHash()).isEqualTo(2758726560L);
        assertThat(profile.characters()).hasSize(2).anySatisfy(c -> {
            assertThat(c.className()).isEqualTo("hunter");
            assertThat(c.light()).isEqualTo(2010);
            assertThat(c.minutesPlayed()).isEqualTo(61_440);
        });
        assertThat(profile.activity().characterId()).isEqualTo("2305843009260000001");
        assertThat(profile.activity().activityHash()).isEqualTo(2122313384L);
        assertThat(profile.activity().modeHash()).isEqualTo(2394616003L);
        server.verify();
    }

    @Test
    void aWithheldProfile_isPrivate_andBungiesPrivacyErrorToo() throws IOException {
        answer(PROFILE, HttpStatus.OK, "profile-private.json");
        answer(PROFILE, HttpStatus.OK, "error-privacy.json");

        assertThat(client.profile(3, "4611686018467284386", "t").profilePrivate()).isTrue();
        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieExceptions.Private.class);
        server.verify();
    }

    @Test
    void onlyDestinyAccountNotFound_isNotFound() throws IOException {
        answer(PROFILE, HttpStatus.OK, "error-not-found.json");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
                        .body(fixture("not-json.html")));

        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieExceptions.NotFound.class);
        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieUnavailableException.class);
        server.verify();
    }

    @Test
    void aGarbledOrWrongShaped2xx_isAnError_neverAbsent() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withSuccess(fixture("not-json.html"), MediaType.TEXT_HTML));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withSuccess("{\"Response\":{},\"ErrorCode\":1}", MediaType.APPLICATION_JSON));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                    .isInstanceOf(BungieUnavailableException.class);
        }
        server.verify();
    }

    @Test
    void aComponentWithoutDataThatIsNotPrivate_isEmpty_notAnError() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withSuccess("{\"Response\":{\"profile\":{\"data\":{\"currentGuardianRank\":3},"
                        + "\"privacy\":1},\"characters\":{\"privacy\":1},\"characterActivities\":{\"privacy\":1}},"
                        + "\"ErrorCode\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withSuccess("{\"Response\":{\"profile\":{\"privacy\":1}},\"ErrorCode\":1}",
                        MediaType.APPLICATION_JSON));

        BungieData.Profile noCharacters = client.profile(3, "4611686018467284386", "t");
        assertThat(noCharacters.guardianRank()).isEqualTo(3);
        assertThat(noCharacters.characters()).isEmpty();
        assertThat(noCharacters.activity()).isNull();
        assertThat(noCharacters.charactersPrivate()).isFalse();
        BungieData.Profile noProfileData = client.profile(3, "4611686018467284386", "t");
        assertThat(noProfileData.profilePrivate()).isFalse();
        assertThat(noProfileData.guardianRank()).isNull();
        server.verify();
    }

    @Test
    void aRedirect_isNeverFollowed() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                        .location(URI.create("https://www.bungie.net/en/Maintenance")));

        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieUnavailableException.class).hasMessageContaining("redirect");
        server.verify();
    }

    @Test
    void anExpiredToken_isAuthRefused_fromBothTheStatusAndTheCode() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-auth.json")));
        answer(PROFILE, HttpStatus.OK, "error-auth.json");

        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieExceptions.AuthRefused.class);
        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieExceptions.AuthRefused.class);
        server.verify();
    }

    @Test
    void throttleSeconds_and429_pauseEveryCall_cappedAtTenMinutes() throws IOException {
        answer(PROFILE, HttpStatus.SERVICE_UNAVAILABLE, "error-throttle.json");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "86400"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                    .isInstanceOf(BungieUnavailableException.class);
        }
        verify(limiter).pause(Duration.ofSeconds(30));
        verify(limiter).pause(Duration.ofMinutes(10));
        verify(limiter).pause(Duration.ofSeconds(60));
        server.verify();
    }

    @Test
    void maintenance_isAnOutage() throws IOException {
        answer(PROFILE, HttpStatus.SERVICE_UNAVAILABLE, "error-maintenance.json");

        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieUnavailableException.class).hasMessageContaining("5");
        verify(limiter, never()).pause(org.mockito.ArgumentMatchers.any());
        server.verify();
    }

    @Test
    void aRefusedLimiter_sendsNothing() {
        when(limiter.budget()).thenReturn((FetchBudget) () -> false);

        assertThatThrownBy(() -> client.profile(3, "4611686018467284386", "t"))
                .isInstanceOf(BungieUnavailableException.class);
        server.verify();
    }

    @Test
    void triumphScore_readsTheActiveScore_nullWhenWithheld() throws IOException {
        answer(PROFILE + "?components=900", HttpStatus.OK, "records.json");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(PROFILE)))
                .andRespond(withSuccess("{\"Response\":{\"profileRecords\":{\"privacy\":2}},\"ErrorCode\":1}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.triumphScore(3, "4611686018467284386", "t")).isEqualTo(18_250);
        assertThat(client.triumphScore(3, "4611686018467284386", "t")).isNull();
        server.verify();
    }

    @Test
    void definitionsAndTheSeason_needNoMemberToken() throws IOException {
        server.expect(requestTo("https://www.bungie.net/Platform/Destiny2/Manifest/DestinyActivityDefinition/2122313384/?lc=fr"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(fixture("activity-definition.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.bungie.net/Platform/Settings/"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(fixture("settings.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.bungie.net/Platform/Destiny2/Manifest/DestinySeasonDefinition/2758726560/?lc=en"))
                .andRespond(withSuccess(fixture("season-definition.json"), MediaType.APPLICATION_JSON));

        assertThat(client.definition("DestinyActivityDefinition", 2122313384L, "fr").name()).isEqualTo("The Glassway");
        long season = client.currentSeasonHash();
        assertThat(season).isEqualTo(2758726560L);
        BungieData.Season def = client.season(season, "en");
        assertThat(def.number()).isEqualTo(28);
        assertThat(def.name()).isEqualTo("Monument of Triumph");
        assertThat(def.startsAt()).isEqualTo(Instant.parse("2025-12-02T17:00:00Z"));
        assertThat(acquired).hasValue(3);
        server.verify();
    }

    @Test
    void anUnsignedHash_isWrittenAsBungieDoes() throws IOException {
        server.expect(requestTo("https://www.bungie.net/Platform/Destiny2/Manifest/DestinyActivityDefinition/4294967295/?lc=en"))
                .andRespond(withSuccess(fixture("activity-definition.json"), MediaType.APPLICATION_JSON));

        assertThat(client.definition("DestinyActivityDefinition", 4294967295L, "en").name()).isNotNull();
        server.verify();
    }

    @Test
    void locales_mapToBungiesCodes() {
        assertThat(BungieData.locale(java.util.Locale.FRANCE)).isEqualTo("fr");
        assertThat(BungieData.locale(java.util.Locale.forLanguageTag("pt-BR"))).isEqualTo("pt-br");
        assertThat(BungieData.locale(java.util.Locale.forLanguageTag("es-MX"))).isEqualTo("es-mx");
        assertThat(BungieData.locale(java.util.Locale.forLanguageTag("es-ES"))).isEqualTo("es");
        assertThat(BungieData.locale(java.util.Locale.forLanguageTag("zh-TW"))).isEqualTo("zh-cht");
        assertThat(BungieData.locale(java.util.Locale.forLanguageTag("zh-CN"))).isEqualTo("zh-chs");
        assertThat(BungieData.locale(java.util.Locale.forLanguageTag("nl"))).isEqualTo("en");
        assertThat(BungieData.locale(null)).isEqualTo("en");
        assertThat(BungieData.platform(3)).isEqualTo("steam");
        assertThat(BungieData.platform(6)).isEqualTo("epic");
        assertThat(BungieData.platform(99)).isNull();
        assertThat(BungieData.platform(null)).isNull();
    }
}
