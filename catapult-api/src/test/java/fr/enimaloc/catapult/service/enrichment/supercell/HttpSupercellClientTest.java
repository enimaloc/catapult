package fr.enimaloc.catapult.service.enrichment.supercell;

import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import fr.enimaloc.catapult.getter.supercell.SupercellApiKeyRotator;
import fr.enimaloc.catapult.repository.supercell.SupercellApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;
import fr.enimaloc.catapult.service.supercell.SupercellTestKeys;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSupercellClientTest {

    static final String KEY = SupercellTestKeys.clash("one");
    static final String OTHER = SupercellTestKeys.clash("two");
    static final URI COC = URI.create("https://api.clashofclans.com/v1/players/%232PP");
    static final URI CR = URI.create("https://api.clashroyale.com/v1/players/%232PP");
    static final URI BS = URI.create("https://api.brawlstars.com/v1/players/%232PP");

    private MockRestServiceServer server;
    private SupercellApiKeyRepository repository;
    private SupercellApiKeyRotator rotator;
    private SupercellRateLimiter limiter;
    private final List<String> buckets = new ArrayList<>();
    private HttpSupercellClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        repository = mock(SupercellApiKeyRepository.class);
        pool(new SupercellApiKeyEntry(KEY, "clash-of-clans"), new SupercellApiKeyEntry(KEY + "r", "clash-royale"),
                new SupercellApiKeyEntry(KEY + "b", "brawl-stars"));
        limiter = mock(SupercellRateLimiter.class);
        when(limiter.acquire(anyString())).thenAnswer(inv -> buckets.add(inv.getArgument(0)));
        client = new HttpSupercellClient(builder.build(), rotator, limiter);
    }

    private void pool(SupercellApiKeyEntry... entries) {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(entries));
        if (rotator == null) {
            rotator = new SupercellApiKeyRotator(repository, new SimpleMeterRegistry());
        } else {
            rotator.refreshKeys();
        }
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/supercell/" + name), StandardCharsets.UTF_8);
    }

    private void answer(URI uri, String key, HttpStatus status, String fixture) throws IOException {
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(fixture(fixture)));
    }

    @Test
    void clashOfClans_readsTheProjection_withTheKeyInTheHeaderOnly_andTheTagEncoded() throws IOException {
        answer(COC, KEY, HttpStatus.OK, "coc-player.json");

        SupercellPlayer player = client.player(SupercellGame.CLASH_OF_CLANS, "#2PP");

        assertThat(player.tag()).isEqualTo("#2PP");
        assertThat(player.name()).isEqualTo("Chief");
        var coc = player.clashOfClans();
        assertThat(coc.townHallLevel()).isEqualTo(16);
        assertThat(coc.trophies()).isEqualTo(5200);
        assertThat(coc.bestTrophies()).isEqualTo(5600);
        assertThat(coc.league()).isEqualTo("Legend League");
        assertThat(coc.leagueIconUrl()).endsWith("/leagues/288/l.png");
        assertThat(coc.clan().name()).isEqualTo("Catapult");
        assertThat(coc.heroes()).hasSize(3).first().satisfies(h -> assertThat(h.level()).isEqualTo(90));
        assertThat(player.clashRoyale()).isNull();
        assertThat(buckets).hasSize(1).allMatch(b -> b.startsWith("key:")).noneMatch(b -> b.contains(KEY));
        assertThat(player.toString()).doesNotContain(KEY);
        server.verify();
    }

    @Test
    void clashRoyale_readsTheProjection() throws IOException {
        answer(CR, KEY + "r", HttpStatus.OK, "cr-player.json");

        var cr = client.player(SupercellGame.CLASH_ROYALE, "#2PP").clashRoyale();

        assertThat(cr.trophies()).isEqualTo(7400);
        assertThat(cr.bestTrophies()).isEqualTo(7600);
        assertThat(cr.arena()).isEqualTo("Legendary Arena");
        assertThat(cr.threeCrownWins()).isEqualTo(900);
        assertThat(cr.pathOfLegendLeague()).isEqualTo(4);
        assertThat(cr.currentDeck()).extracting(SupercellModels.Card::name).containsExactly("Hog Rider", "Musketeer");
    }

    @Test
    void brawlStars_readsTheProjection() throws IOException {
        answer(BS, KEY + "b", HttpStatus.OK, "bs-player.json");

        var bs = client.player(SupercellGame.BRAWL_STARS, "#2PP").brawlStars();

        assertThat(bs.trophies()).isEqualTo(31000);
        assertThat(bs.highestTrophies()).isEqualTo(31500);
        assertThat(bs.trioVictories()).isEqualTo(2400);
        assertThat(bs.club().name()).isEqualTo("Catapult");
        assertThat(bs.brawlers()).hasSize(2);
    }

    @Test
    void notATag_isRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "2PP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP/../x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(buckets).isEmpty();
    }

    @Test
    void anUnreadableOrWrongShaped2xx_isAnError() {
        List<String> bodies = List.of("{}", "[]", "<html></html>", "", "{\"tag\":\"#2PP\",\"name\":\"x\"}",
                "{\"tag\":\"#9YY\",\"name\":\"x\",\"trophies\":1,\"townHallLevel\":3}",
                "{\"name\":\"x\",\"trophies\":1,\"townHallLevel\":3}",
                "{\"tag\":\"#2PP\",\"name\":\"x\",\"trophies\":1}");
        for (String body : bodies) {
            server.expect(requestTo(COC)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < bodies.size(); i++) {
            assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                    .isInstanceOf(SupercellUnavailableException.class);
        }
    }

    @Test
    void onlyTheNotFound404_isNotFound() throws IOException {
        answer(COC, KEY, HttpStatus.NOT_FOUND, "not-found.json");
        server.expect(requestTo(COC)).andRespond(withStatus(HttpStatus.NOT_FOUND).body("<html>404</html>"));
        server.expect(requestTo(COC)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"reason\":\"unknownRoute\"}"));

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellNotFoundException.class);
        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
    }

    @Test
    void aRedirect_isNeverFollowed_andIsAnError() {
        server.expect(requestTo(COC))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example/steal"));

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
        server.verify();
    }

    @Test
    void theTransport_neverFollowsARedirect() {
        assertThat(HttpSupercellClient.httpClient().followRedirects())
                .isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
        assertThat(HttpSupercellClient.requestFactory()).isNotNull();
    }

    @Test
    void aKeyRefusedFromThisIp_isSetAsideForItsGame_andAnotherKeyIsTriedOnce() throws IOException {
        pool(new SupercellApiKeyEntry(KEY, "clash-of-clans"), new SupercellApiKeyEntry(OTHER, "clash-of-clans"),
                new SupercellApiKeyEntry(KEY + "r", "clash-royale"));
        String first = rotator.nextKey(SupercellGame.CLASH_OF_CLANS).orElseThrow();
        String second = first.equals(KEY) ? OTHER : KEY;
        // the next call starts with the key after "first"
        answer(COC, second, HttpStatus.FORBIDDEN, "invalid-ip.json");
        answer(COC, first, HttpStatus.OK, "coc-player.json");

        assertThat(client.player(SupercellGame.CLASH_OF_CLANS, "#2PP").name()).isEqualTo("Chief");

        assertThat(rotator.blocked(SupercellGame.CLASH_OF_CLANS, second)).isTrue();
        assertThat(rotator.blocked(SupercellGame.CLASH_OF_CLANS, first)).isFalse();
        assertThat(rotator.nextKey(SupercellGame.CLASH_ROYALE)).contains(KEY + "r");
        server.verify();
    }

    @Test
    void whenEveryKeyIsRefusedFromThisIp_nothingMoreIsSent() throws IOException {
        answer(COC, KEY, HttpStatus.FORBIDDEN, "invalid-ip.json");

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class).hasMessageNotContaining("198.51.100.23");
        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);

        assertThat(buckets).hasSize(1);
        server.verify();
    }

    @Test
    void anyOther403_failsThatCallOnly_andTheKeyStaysInUse() throws IOException {
        answer(COC, KEY, HttpStatus.FORBIDDEN, "access-denied.json");
        server.expect(requestTo(COC)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        answer(COC, KEY, HttpStatus.OK, "coc-player.json");

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
        assertThat(rotator.blocked(SupercellGame.CLASH_OF_CLANS, KEY)).isFalse();
        assertThat(client.player(SupercellGame.CLASH_OF_CLANS, "#2PP").name()).isEqualTo("Chief");
    }

    @Test
    void a429_pausesThatKeysBucketOnly_forItsRetryAfterAtMostTenMinutes() throws IOException {
        server.expect(requestTo(COC)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "86400").body(fixture("throttled.json")));

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);

        verify(limiter).pause(eq(buckets.getFirst()), eq(Duration.ofMinutes(10)));
        assertThat(rotator.blocked(SupercellGame.CLASH_OF_CLANS, KEY)).isFalse();
    }

    @Test
    void retryAfter_isReadCappedAndDefaulted() {
        assertThat(HttpSupercellClient.retryAfter("30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(HttpSupercellClient.retryAfter("99999")).isEqualTo(Duration.ofMinutes(10));
        assertThat(HttpSupercellClient.retryAfter(null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(HttpSupercellClient.retryAfter("0")).isEqualTo(Duration.ofMinutes(1));
        assertThat(HttpSupercellClient.retryAfter("soon")).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void maintenance_andServerErrors_areOutages() throws IOException {
        server.expect(requestTo(COC)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body(fixture("maintenance.json")));
        server.expect(requestTo(COC)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
    }

    @Test
    void aRefusedLimiterSlot_sendsNothing() {
        when(limiter.acquire(anyString())).thenReturn(false);
        server.expect(org.springframework.test.web.client.ExpectedCount.never(), requestTo(COC));

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
        server.verify();
    }

    @Test
    void aKeyTheLimiterRefusesNow_isPassedOverForAnotherKeyOfTheGame() throws IOException {
        pool(new SupercellApiKeyEntry(KEY, "clash-of-clans"), new SupercellApiKeyEntry(OTHER, "clash-of-clans"));
        String paused = "key:" + fr.enimaloc.catapult.service.ApiKeyHasher.id(KEY);
        when(limiter.acquire(anyString())).thenAnswer(inv -> !paused.equals(inv.getArgument(0)));
        answer(COC, OTHER, HttpStatus.OK, "coc-player.json");
        answer(COC, OTHER, HttpStatus.OK, "coc-player.json");

        // whichever key the rotation starts with, the paused one is never sent
        assertThat(client.player(SupercellGame.CLASH_OF_CLANS, "#2PP").name()).isEqualTo("Chief");
        assertThat(client.player(SupercellGame.CLASH_OF_CLANS, "#2PP").name()).isEqualTo("Chief");
        server.verify();
    }

    @Test
    void whenTheLimiterRefusesEveryKey_nothingIsSent() {
        pool(new SupercellApiKeyEntry(KEY, "clash-of-clans"), new SupercellApiKeyEntry(OTHER, "clash-of-clans"));
        when(limiter.acquire(anyString())).thenReturn(false);
        server.expect(org.springframework.test.web.client.ExpectedCount.never(), requestTo(COC));

        assertThatThrownBy(() -> client.player(SupercellGame.CLASH_OF_CLANS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class).hasMessageContaining("rate limit");
        server.verify();
    }

    @Test
    void aGameWithoutKey_sendsNothing() {
        pool(new SupercellApiKeyEntry(KEY, "clash-of-clans"));

        assertThatThrownBy(() -> client.player(SupercellGame.BRAWL_STARS, "#2PP"))
                .isInstanceOf(SupercellUnavailableException.class);
        verify(limiter, never()).acquire(any());
    }

    @Test
    void readLimited_refusesWhatIsTooBig() throws IOException {
        assertThat(HttpSupercellClient.readLimited(new ByteArrayInputStream(new byte[4]), 4)).hasSize(4);
        assertThat(HttpSupercellClient.readLimited(new ByteArrayInputStream(new byte[5]), 4)).isNull();
    }
}
