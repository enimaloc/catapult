package fr.enimaloc.catapult.service.enrichment.hltb;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HltbScraperTest {

    private static final String SEARCH = "https://howlongtobeat.com/api/search/site";
    private static final String USER_AGENT = "Catapult (+https://catapult.example)";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T12:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private MockRestServiceServer server;
    private HttpHltbScraper scraper;

    @BeforeEach
    void setUp() {
        properties.getHltb().setBreakerThreshold(2);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        scraper = new HttpHltbScraper(builder, properties, "https://catapult.example/", clock, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        });
    }

    private void expectInit(String token) {
        server.expect(request -> assertThat(request.getURI().toString()).startsWith(SEARCH + "/init?t="))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", USER_AGENT))
                .andRespond(withSuccess("{\"token\":\"" + token + "\"}", MediaType.APPLICATION_JSON));
    }

    private void expectSearch(String token, String json) {
        server.expect(requestTo(SEARCH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("User-Agent", USER_AGENT))
                .andExpect(header("x-auth-token", token))
                .andExpect(jsonPath("$.searchType").value("games"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static String fixture() throws IOException {
        return new ClassPathResource("enrichment/hltb-search.json").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void parsesSearchFixture_bestMatchByName() throws IOException {
        expectInit("abc");
        server.expect(requestTo(SEARCH))
                .andExpect(header("x-auth-token", "abc"))
                .andExpect(jsonPath("$.searchTerms[0]").value("Hollow"))
                .andExpect(jsonPath("$.searchTerms[1]").value("Knight"))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        assertThat(scraper.search("Hollow Knight")).contains(new TimeToBeat(97_163L, 149_918L, 236_144L, 9559,
                TimeToBeat.Origin.HLTB, "https://howlongtobeat.com/game/26286"));
        server.verify();
    }

    @Test
    void noCloseMatch_empty() throws IOException {
        expectInit("abc");
        expectSearch("abc", fixture());

        assertThat(scraper.search("Celeste")).isEmpty();
    }

    @Test
    void expiredToken_refreshedOnceAndRetried() throws IOException {
        expectInit("old");
        server.expect(requestTo(SEARCH)).andExpect(header("x-auth-token", "old"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectInit("new");
        expectSearch("new", fixture());

        assertThat(scraper.search("Hollow Knight")).isPresent();
        server.verify();
    }

    @Test
    void throttle_oneRequestEveryInterval() throws IOException {
        expectInit("abc");
        expectSearch("abc", fixture());
        expectSearch("abc", fixture());

        scraper.search("Hollow Knight");
        scraper.search("Hollow Knight");

        // init, then two searches: each request after the first waits for the 2 s interval
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void throttle_noWaitOnceTheIntervalHasPassed() throws IOException {
        expectInit("abc");
        expectSearch("abc", fixture());
        expectSearch("abc", fixture());

        scraper.search("Hollow Knight");
        clock.advance(Duration.ofSeconds(5));
        scraper.search("Hollow Knight");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void breaker_opensAfterThreshold_thenSkipsUntilPause() throws IOException {
        server.expect(request -> assertThat(request.getURI().toString()).startsWith(SEARCH + "/init"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(request -> assertThat(request.getURI().toString()).startsWith(SEARCH + "/init"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> scraper.search("Hollow Knight")).isInstanceOf(HltbScraper.UnavailableException.class);
        assertThatThrownBy(() -> scraper.search("Hollow Knight")).isInstanceOf(HltbScraper.UnavailableException.class);
        // open: no request reaches the site
        assertThatThrownBy(() -> scraper.search("Hollow Knight")).isInstanceOf(HltbScraper.UnavailableException.class);
        clock.advance(Duration.ofMinutes(29));
        assertThatThrownBy(() -> scraper.search("Hollow Knight")).isInstanceOf(HltbScraper.UnavailableException.class);
        server.verify();

        server.reset();
        clock.advance(Duration.ofMinutes(1));
        expectInit("abc");
        expectSearch("abc", fixture());
        assertThat(scraper.search("Hollow Knight")).isPresent();
        server.verify();
    }

    @Test
    void breaker_successResetsTheFailureCount() throws IOException {
        server.expect(request -> assertThat(request.getURI().toString()).startsWith(SEARCH + "/init"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        expectInit("abc");
        expectSearch("abc", fixture());
        expectSearch("abc", "{\"data\":\"not a list\"}");
        expectSearch("abc", fixture());

        assertThatThrownBy(() -> scraper.search("Hollow Knight")).isInstanceOf(HltbScraper.UnavailableException.class);
        assertThat(scraper.search("Hollow Knight")).isPresent();
        assertThatThrownBy(() -> scraper.search("Hollow Knight")).isInstanceOf(HltbScraper.UnavailableException.class);
        // one failure since the success: still closed
        assertThat(scraper.search("Hollow Knight")).isPresent();
        server.verify();
    }

    /** A clock the test (and the fake sleeper) moves forward by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
