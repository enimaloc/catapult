package fr.enimaloc.catapult.service.enrichment.twitch;

import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HelixTwitchActivityClientTest {

    private static final String BASE = "https://api.twitch.tv/helix";

    private MockRestServiceServer server;
    private TwitchTokenService tokens;
    private HelixTwitchActivityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(TwitchTokenService.class);
        when(tokens.getAppAccessToken()).thenReturn("tok");
        client = new HelixTwitchActivityClient(builder, tokens, "cid");
    }

    /** A page of {@code n} streams whose viewers start at {@code firstViewers}, with the given cursor. */
    private static String streamsPage(int n, int firstViewers, String cursor) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"user_name\":\"Chan").append(firstViewers + i).append("\",\"user_login\":\"chan")
                    .append(firstViewers + i).append("\",\"title\":\"t\",\"viewer_count\":").append(firstViewers + i)
                    .append(",\"language\":\"fr\"}");
        }
        sb.append("],\"pagination\":").append(cursor == null ? "{}" : "{\"cursor\":\"" + cursor + "\"}").append('}');
        return sb.toString();
    }

    private void expectPage(String url, String body) {
        server.expect(once(), requestTo(url))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andExpect(header("Client-Id", "cid"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void stopsAfterFivePages() {
        String first = BASE + "/streams?game_id=7&first=100";
        expectPage(first, streamsPage(100, 1, "c1"));
        for (int p = 1; p < 5; p++) {
            expectPage(first + "&after=c" + p, streamsPage(100, 1, "c" + (p + 1)));
        }

        TwitchLives lives = client.lives("7", null);

        server.verify();
        assertThat(lives.liveCount()).isEqualTo(500);
        assertThat(lives.approximate()).isTrue();
    }

    @Test
    void sumsViewersAndKeepsTopTen() {
        String first = BASE + "/streams?game_id=7&first=100&language=fr";
        expectPage(first, streamsPage(100, 1, "c1"));
        expectPage(first + "&after=c1", streamsPage(30, 101, null));

        TwitchLives lives = client.lives("7", "fr");

        server.verify();
        assertThat(lives.liveCount()).isEqualTo(130);
        assertThat(lives.viewers()).isEqualTo(130L * 131 / 2);
        assertThat(lives.approximate()).isFalse();
        assertThat(lives.top()).hasSize(10);
        assertThat(lives.top().get(0).viewers()).isEqualTo(130);
        assertThat(lives.top().get(9).viewers()).isEqualTo(121);
        assertThat(lives.top().get(0).url()).isEqualTo("https://twitch.tv/chan130");
    }

    @Test
    void noStreams_isZeroLives() {
        expectPage(BASE + "/streams?game_id=7&first=100", "{\"data\":[],\"pagination\":{}}");

        TwitchLives lives = client.lives("7", null);

        assertThat(lives.liveCount()).isZero();
        assertThat(lives.top()).isEmpty();
    }

    @Test
    void categoryByIgdbId_readsTheFirstGame() {
        expectPage(BASE + "/games?igdb_id=1942", "{\"data\":[{\"id\":\"32982\",\"name\":\"Grand Theft Auto V\"}]}");

        assertThat(client.categoryByIgdbId("1942")).contains(new TwitchCategory("32982", "Grand Theft Auto V"));
    }

    @Test
    void categoryById_emptyWhenUnknown() {
        expectPage(BASE + "/games?id=1", "{\"data\":[]}");

        assertThat(client.categoryById("1")).isEqualTo(Optional.empty());
    }

    @Test
    void clips_readsTheTopClips() {
        Instant since = Instant.parse("2026-11-14T00:00:00Z");
        expectPage(BASE + "/clips?game_id=7&started_at=2026-11-14T00:00:00Z&first=10",
                "{\"data\":[{\"title\":\"Wow\",\"broadcaster_name\":\"Chan\",\"view_count\":42,\"duration\":29.5,"
                        + "\"url\":\"https://clips.twitch.tv/x\",\"thumbnail_url\":\"https://t/x.jpg\"}]}");

        assertThat(client.clips("7", since).top()).containsExactly(
                new TwitchClips.Clip("Wow", "Chan", 42, 29.5, "https://clips.twitch.tv/x", "https://t/x.jpg"));
    }

    @Test
    void emptyToken_sendsNothingAndFails() {
        when(tokens.getAppAccessToken()).thenReturn("");

        assertThatThrownBy(() -> client.lives("7", null)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void withoutClientId_isNotConfigured() {
        HelixTwitchActivityClient unconfigured = new HelixTwitchActivityClient(RestClient.builder(), tokens, "");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.categoryById("1")).isInstanceOf(IllegalStateException.class);
    }
}
