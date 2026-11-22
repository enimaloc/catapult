package fr.enimaloc.catapult.service.enrichment.xboxstats;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService.XstsSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpXboxStatsClientTest {

    private static final String HISTORY = "https://titlehub.xboxlive.com/users/xuid(2535400000000000)/titles/"
            + "titlehistory/decoration/achievement,detail?maxItems=1000";
    private static final String ACHIEVEMENTS = "https://achievements.xboxlive.com/users/xuid(2535400000000000)/"
            + "achievements?titleId=1791712750&maxItems=1000";
    private static final String USERSTATS = "https://userstats.xboxlive.com/batch";

    private final UUID member = UUID.randomUUID();
    private final XstsSession session = new XstsSession("secret-token", "uhs123", "2535400000000000");
    private MockRestServiceServer server;
    private XboxStatsRateLimiter limiter;
    private FetchBudget budget;
    private HttpXboxStatsClient client;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.US);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        limiter = mock(XboxStatsRateLimiter.class);
        budget = mock(FetchBudget.class);
        when(budget.tryAcquire()).thenReturn(true);
        when(limiter.forMember(member)).thenReturn(budget);
        client = new HttpXboxStatsClient(builder.build(), limiter);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/xboxstats/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void parsesTheTitleHistoryWithItsHeaders() throws IOException {
        server.expect(once(), requestTo(HISTORY))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "XBL3.0 x=uhs123;secret-token"))
                .andExpect(header("x-xbl-contract-version", "2"))
                .andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"))
                .andExpect(header(HttpHeaders.USER_AGENT, "Catapult/1.0 (+https://catapult.enimaloc.fr)"))
                .andRespond(withSuccess(fixture("titlehistory.json"), MediaType.APPLICATION_JSON));

        List<XboxTitle> titles = client.titleHistory(member, session, 1000, Locale.US);

        assertThat(titles).hasSize(3);
        XboxTitle forza = titles.getFirst();
        assertThat(forza.titleId()).isEqualTo("1791712750");
        assertThat(forza.productIds()).containsExactly("9MVXMVT8ZKWC", "9NKX70BBCDRN");
        assertThat(forza.totalAchievements()).isEqualTo(20);
        assertThat(forza.currentGamerscore()).isEqualTo(450);
        assertThat(forza.lastTimePlayed()).isEqualTo(Instant.parse("2026-11-20T18:30:00Z"));
        server.verify();
    }

    @Test
    void usesTheGivenLocaleWithItsRegion_notTheThreads() {
        // the thread's locale is never read: the reads run on worker threads
        LocaleContextHolder.setLocale(Locale.GERMANY);
        server.expect(once(), requestTo(HISTORY)).andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CA"))
                .andRespond(withSuccess("{\"titles\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.titleHistory(member, session, 1000, Locale.CANADA_FRENCH)).isEmpty();
        server.verify();
    }

    @Test
    void aLanguageAloneGetsItsUsualRegion() {
        assertThat(HttpXboxStatsClient.acceptLanguage(Locale.FRENCH)).isEqualTo("fr-FR");
        assertThat(HttpXboxStatsClient.acceptLanguage(Locale.ENGLISH)).isEqualTo("en-US");
        assertThat(HttpXboxStatsClient.acceptLanguage(Locale.forLanguageTag("tlh"))).isEqualTo("en-US");
        assertThat(HttpXboxStatsClient.acceptLanguage(null)).isEqualTo("en-US");
        assertThat(HttpXboxStatsClient.acceptLanguage(Locale.UK)).isEqualTo("en-GB");
    }

    @Test
    void parsesAchievementsWithRarityAndHidesLockedSecrets() throws IOException {
        server.expect(once(), requestTo(ACHIEVEMENTS))
                .andRespond(withSuccess(fixture("achievements-v2.json"), MediaType.APPLICATION_JSON));

        List<Achievement> all = client.achievements(member, session, "1791712750", Locale.US);

        assertThat(all).hasSize(3);
        Achievement rare = all.get(1);
        assertThat(rare.unlocked()).isTrue();
        assertThat(rare.unlockedAt()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
        assertThat(rare.rarity()).isEqualByComparingTo(new BigDecimal("3.2"));
        assertThat(rare.points()).isEqualTo(50);
        assertThat(rare.iconUrl()).isEqualTo("https://images.example/2.png");
        Achievement secret = all.get(2);
        assertThat(secret.unlocked()).isFalse();
        assertThat(secret.secret()).isTrue();
        assertThat(secret.name()).isNull();
        assertThat(secret.description()).isNull();
        assertThat(secret.unlockedAt()).isNull();
    }

    @Test
    void missingRarityIsNull() throws IOException {
        server.expect(once(), requestTo(ACHIEVEMENTS))
                .andRespond(withSuccess(fixture("achievements-v2-norarity.json"), MediaType.APPLICATION_JSON));

        assertThat(client.achievements(member, session, "1791712750", Locale.US)).hasSize(3)
                .allSatisfy(a -> assertThat(a.rarity()).isNull());
    }

    @Test
    void readsMinutesPlayedFromUserstats() throws IOException {
        server.expect(once(), requestTo(USERSTATS)).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("x-xbl-contract-version", "2"))
                .andExpect(jsonPath("$.xuids[0]").value("2535400000000000"))
                .andExpect(jsonPath("$.stats[0].name").value("MinutesPlayed"))
                .andExpect(jsonPath("$.stats[0].titleid").value("1791712750"))
                .andRespond(withSuccess(fixture("userstats-batch.json"), MediaType.APPLICATION_JSON));

        assertThat(client.minutesPlayed(member, session, "1791712750")).contains(610L);
    }

    @Test
    void absentMinutesIsEmpty() {
        server.expect(once(), requestTo(USERSTATS)).andRespond(withSuccess(
                "{\"statlistscollection\":[{\"stats\":[]}]}", MediaType.APPLICATION_JSON));

        assertThat(client.minutesPlayed(member, session, "1")).isEqualTo(Optional.empty());
    }

    @Test
    void everyRequestCountsForTheMember() throws IOException {
        server.expect(requestTo(HISTORY)).andRespond(withSuccess(fixture("titlehistory.json"),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(ACHIEVEMENTS)).andRespond(withSuccess(fixture("achievements-v2.json"),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERSTATS)).andRespond(withSuccess(fixture("userstats-batch.json"),
                MediaType.APPLICATION_JSON));

        client.titleHistory(member, session, 1000, Locale.US);
        client.achievements(member, session, "1791712750", Locale.US);
        client.minutesPlayed(member, session, "1791712750");

        verify(limiter, times(3)).forMember(member);
        verify(budget, times(3)).tryAcquire();
    }

    @Test
    void refusedBudgetSendsNothing() {
        when(budget.tryAcquire()).thenReturn(false);

        assertThatThrownBy(() -> client.titleHistory(member, session, 1000, Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);
        server.verify();
    }

    @Test
    void unauthorizedAndForbiddenAchievementsAreForbidden() {
        server.expect(once(), requestTo(ACHIEVEMENTS)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.achievements(member, session, "1791712750", Locale.US))
                .isInstanceOf(XboxStatsForbiddenException.class);

        server.reset();
        server.expect(once(), requestTo(USERSTATS)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.minutesPlayed(member, session, "1"))
                .isInstanceOf(XboxStatsForbiddenException.class);
    }

    @Test
    void tooManyRequestsPausesTheMemberWithRetryAfter() {
        server.expect(once(), requestTo(HISTORY)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "120"));

        assertThatThrownBy(() -> client.titleHistory(member, session, 1000, Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);
        verify(limiter).pause(member, Duration.ofSeconds(120));
    }

    @Test
    void tooManyRequestsWithoutRetryAfterPausesAMinute() {
        server.expect(once(), requestTo(HISTORY)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.titleHistory(member, session, 1000, Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);
        verify(limiter).pause(member, Duration.ofSeconds(60));
    }

    @Test
    void serviceUnavailableRedirectAndGarbageAreOutages() {
        server.expect(once(), requestTo(HISTORY)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.titleHistory(member, session, 1000, Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);
        verify(limiter, never()).pause(any(), any());

        server.reset();
        server.expect(once(), requestTo(HISTORY)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "https://elsewhere.example/"));
        assertThatThrownBy(() -> client.titleHistory(member, session, 1000, Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);

        server.reset();
        server.expect(once(), requestTo(HISTORY)).andRespond(withSuccess("<html>oops</html>",
                MediaType.TEXT_HTML));
        assertThatThrownBy(() -> client.titleHistory(member, session, 1000, Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);
    }

    @Test
    void wellFormedButWrongShapedAnswersAreOutages() {
        server.expect(once(), requestTo(HISTORY)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.titleHistory(member, session, 1000, Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);

        server.reset();
        server.expect(once(), requestTo(ACHIEVEMENTS)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.achievements(member, session, "1791712750", Locale.US))
                .isInstanceOf(XboxStatsUnavailableException.class);

        server.reset();
        server.expect(once(), requestTo(USERSTATS)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.minutesPlayed(member, session, "1"))
                .isInstanceOf(XboxStatsUnavailableException.class);
    }

    @Test
    void emptyAchievementsArrayIsAnEmptyList() {
        server.expect(once(), requestTo(ACHIEVEMENTS)).andRespond(withSuccess("{\"achievements\":[]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.achievements(member, session, "1791712750", Locale.US)).isEmpty();
    }
}
