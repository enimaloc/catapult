package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** TwitchHelixChannelClient against a mocked Helix: requests sent and failure handling. */
class TwitchHelixChannelClientTest {

    private static final String USERS = "https://api.twitch.tv/helix/users?login=";
    private static final String BANS = "https://api.twitch.tv/helix/moderation/bans?broadcaster_id=b-1&moderator_id=b-1";

    private MockRestServiceServer helix;
    private TwitchHelixChannelClient client;
    private final UserAccount channel = new UserAccount();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        helix = MockRestServiceServer.bindTo(builder).build();
        client = new TwitchHelixChannelClient(builder.build());
        ReflectionTestUtils.setField(client, "twitchClientId", "client-id");
        channel.setId(UUID.randomUUID());
        channel.setTwitchId("b-1");
    }

    private void json(String url, String body) {
        helix.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token"))
                .andExpect(header("Client-Id", "client-id"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void user(String login, String id) {
        json(USERS + login, "{\"data\": [{\"id\": \"" + id + "\", \"display_name\": \"" + login + "\"}]}");
    }

    private void unknownUser(String login) {
        json(USERS + login, "{\"data\": []}");
    }

    @Nested
    class Moderation {
        @Test
        void ban_withReason() {
            user("troll", "t-1");
            helix.expect(requestTo(BANS)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("{\"data\": {\"user_id\": \"t-1\", \"reason\": \"spam\"}}", true))
                    .andRespond(withSuccess());

            client.moderate(channel, "token", "troll", 0, "spam");

            helix.verify();
        }

        @Test
        void timeout_withoutReason() {
            user("troll", "t-1");
            helix.expect(requestTo(BANS)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("{\"data\": {\"user_id\": \"t-1\", \"duration\": 60}}", true))
                    .andRespond(withSuccess());

            client.moderate(channel, "token", "troll", 60, " ");

            helix.verify();
        }

        @Test
        void unknownTarget_orFailures_areSwallowed() {
            unknownUser("ghost");
            user("troll", "t-1");
            helix.expect(requestTo(BANS)).andRespond(withServerError());

            client.moderate(channel, "token", "ghost", 0, null);
            client.moderate(channel, "token", "troll", 0, null);

            helix.verify();
        }

        @Test
        void unban() {
            user("troll", "t-1");
            helix.expect(requestTo(BANS + "&user_id=t-1")).andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess());
            unknownUser("ghost");
            user("other", "o-1");
            helix.expect(requestTo(BANS + "&user_id=o-1")).andRespond(withServerError());

            client.unban(channel, "token", "troll");
            client.unban(channel, "token", "ghost");
            client.unban(channel, "token", "other");

            helix.verify();
        }

        @Test
        void shoutout() {
            String url = "https://api.twitch.tv/helix/chat/shoutouts?from_broadcaster_id=b-1&to_broadcaster_id=";
            user("friend", "f-1");
            helix.expect(requestTo(url + "f-1&moderator_id=b-1")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());
            unknownUser("ghost");
            user("other", "o-1");
            helix.expect(requestTo(url + "o-1&moderator_id=b-1")).andRespond(withServerError());

            client.shoutout(channel, "token", "friend");
            client.shoutout(channel, "token", "ghost");
            client.shoutout(channel, "token", "other");

            helix.verify();
        }

        @Test
        void loginsAreEncodedTwice_andLookupFailuresMeanUnknown() {
            // URLEncoder, then RestClient's own template encoding (harmless: logins are [a-z0-9_])
            helix.expect(requestTo(USERS + "a+b%2526c")).andRespond(withServerError());
            json(USERS + "nobody", "{}");

            client.unban(channel, "token", "a b&c");
            client.unban(channel, "token", "nobody");

            helix.verify();
        }
    }

    @Nested
    class Reads {
        private static final String STREAMS = "https://api.twitch.tv/helix/streams?user_id=b-1";
        private static final String FOLLOWERS = "https://api.twitch.tv/helix/channels/followers?broadcaster_id=b-1&moderator_id=b-1&user_id=";

        @Test
        void streamInfo() {
            json(STREAMS, "{\"data\": [{\"title\": \"Speedrun\", \"game_name\": \"Celeste\", \"viewer_count\": 42,"
                    + " \"started_at\": \"2024-05-01T10:00:00Z\"}]}");
            json(STREAMS, "{\"data\": []}");
            json(STREAMS, "{\"data\": [{\"title\": \"Broken\"}]}");

            assertThat(client.streamInfo(channel, "token")).contains(new TwitchStreamInfo(
                    "Speedrun", "Celeste", 42, Instant.parse("2024-05-01T10:00:00Z")));
            assertThat(client.streamInfo(channel, "token")).isEmpty();
            assertThat(client.streamInfo(channel, "token")).isEmpty();
        }

        @Test
        void userProfile() {
            json(USERS + "fan", "{\"data\": [{\"id\": \"1\", \"display_name\": \"Fan\", \"created_at\": \"2015-01-01T00:00:00Z\"}]}");
            json(USERS + "new", "{\"data\": [{\"id\": \"2\", \"display_name\": \"New\"}]}");
            unknownUser("ghost");

            assertThat(client.userProfile("token", "fan"))
                    .contains(new TwitchUserProfile("Fan", Instant.parse("2015-01-01T00:00:00Z")));
            assertThat(client.userProfile("token", "new")).contains(new TwitchUserProfile("New", null));
            assertThat(client.userProfile("token", "ghost")).isEmpty();
        }

        @Test
        void followedAt_byLoginOrId() {
            user("fan", "f-1");
            json(FOLLOWERS + "f-1", "{\"data\": [{\"followed_at\": \"2020-02-02T00:00:00Z\"}]}");
            unknownUser("ghost");
            json(FOLLOWERS + "42", "{\"data\": []}");
            json(FOLLOWERS + "43", "{}");
            helix.expect(requestTo(FOLLOWERS + "44")).andRespond(withServerError());

            assertThat(client.followedAt(channel, "token", "fan")).contains(Instant.parse("2020-02-02T00:00:00Z"));
            assertThat(client.followedAt(channel, "token", "ghost")).isEmpty();
            assertThat(client.followedAtById(channel, "token", "42")).isEmpty();
            assertThat(client.followedAtById(channel, "token", "43")).isEmpty();
            assertThat(client.followedAtById(channel, "token", "44")).isEmpty();
        }
    }
}
