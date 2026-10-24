package fr.enimaloc.catapult.service.debug;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A member's share of a grouped call, and the cases where none can safely be cut out (nothing is recorded). */
class BatchDebugFilterTest {

    private static RawExchange raw(String url, String responseBody, String error) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(999);
        return new RawExchange(Instant.EPOCH, "GET", URI.create(url), new HttpHeaders(), null, 200, headers,
                responseBody == null ? null : responseBody.getBytes(StandardCharsets.UTF_8), error, 12);
    }

    private static String body(RawExchange r) {
        return new String(r.responseBody(), StandardCharsets.UTF_8);
    }

    @Test
    void steam_encodedCommas_andEverythingElseKept() {
        RawExchange share = BatchDebugFilter.steam(raw(
                "https://api.steampowered.com/x/?key=K&steamids=1%2C2%2C3&format=json",
                "{\"response\":{\"players\":[{\"steamid\":\"1\"},{\"steamid\":\"2\"},{\"steamid\":\"3\"}]}}",
                null), "2");

        assertThat(share.uri().toString()).isEqualTo("https://api.steampowered.com/x/?key=K&steamids=2&format=json");
        assertThat(body(share)).isEqualTo("{\"response\":{\"players\":[{\"steamid\":\"2\"}]}}");
        assertThat(share.status()).isEqualTo(200);
        assertThat(share.durationMs()).isEqualTo(12);
        assertThat(share.responseHeaders().getContentLength()).isEqualTo(-1);
        assertThat(share.responseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void steam_memberNotInThisChunk_noShare() {
        assertThat(BatchDebugFilter.steam(raw("https://api.steampowered.com/x/?steamids=1,3",
                "{\"response\":{\"players\":[]}}", null), "2")).isNull();
    }

    @Test
    void unparseableResponse_noShare() {
        assertThat(BatchDebugFilter.steam(raw("https://api.steampowered.com/x/?steamids=1,2",
                "<html>1 2</html>", null), "2")).isNull();
        assertThat(BatchDebugFilter.twitchUsers(raw("https://api.twitch.tv/helix/users?id=1&id=2",
                "{\"data\":[{\"id\":\"1\"}", null), "2")).isNull();
        assertThat(BatchDebugFilter.minecraftPresence(raw("https://api.minecraftservices.com/presence",
                "[1,2]", null), "2")).isNull();
    }

    @Test
    void noResponseBody_failedCall_keepsTheFilteredRequestAndAnErrorWithoutTheOthers() {
        RawExchange share = BatchDebugFilter.twitchUsers(raw("https://api.twitch.tv/helix/users?id=1&id=2&id=3",
                null, "I/O error on GET https://api.twitch.tv/helix/users?id=1&id=2&id=3: timeout"), "2");

        assertThat(share.uri().toString()).isEqualTo("https://api.twitch.tv/helix/users?id=2");
        assertThat(share.responseBody()).isNull();
        assertThat(share.error()).isEqualTo("I/O error on GET https://api.twitch.tv/helix/users?id=2: timeout");
    }

    @Test
    void twitch_memberNotInThisBatch_noShare() {
        assertThat(BatchDebugFilter.twitchUsers(raw("https://api.twitch.tv/helix/users?id=1&id=3",
                "{\"data\":[]}", null), "2")).isNull();
    }

    @Test
    void minecraftFriends_emptyFlagFollowsTheShare() {
        RawExchange share = BatchDebugFilter.minecraftFriends(raw("https://api.minecraftservices.com/friends",
                "{\"friends\":[{\"profileId\":\"aa\"}],\"incomingRequests\":[],\"outgoingRequests\":[],"
                        + "\"empty\":false}", null), "b-b");

        assertThat(body(share)).isEqualTo(
                "{\"friends\":[],\"incomingRequests\":[],\"outgoingRequests\":[],\"empty\":true}");
    }
}
