package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** MinecraftService against a mocked api.minecraftservices.com: requests sent and replies parsed. */
class MinecraftServiceHttpTest {

    private static final String API = "https://api.minecraftservices.com";
    private static final String FRIENDS_JSON = """
            {"friends": [{"profileId": "p-1", "name": "Alex"}], "incomingRequests": [], "outgoingRequests": [],
             "empty": false}""";
    private static final String PRESENCE_JSON = """
            {"presence": [{"profileId": "p-1", "pmid": "m", "status": "PLAYING_SERVER",
                           "joinInfo": {"value": "srv", "invited": false}, "lastUpdated": "2026-01-01T00:00:00Z"}]}""";

    private MockRestServiceServer mojang;
    private MinecraftService service;
    private final MinecraftService.Token token = new MinecraftService.Token("u", "mc-token", 86400, null, "Bearer", null);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mojang = MockRestServiceServer.bindTo(builder).build();
        service = new MinecraftService(builder.build());
    }

    private void friendsUpdate(String expectedBody) {
        mojang.expect(requestTo(API + "/friends")).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer mc-token"))
                .andExpect(content().json(expectedBody, true))
                .andRespond(withSuccess(FRIENDS_JSON, MediaType.APPLICATION_JSON));
    }

    @Test
    void minecraftToken_isExchangedForTheXboxIdentity() {
        mojang.expect(requestTo(API + "/authentication/login_with_xbox")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"identityToken\": \"XBL3.0 x=hash;xsts\"}", true))
                .andRespond(withSuccess("{\"username\": \"u\", \"access_token\": \"mc\", \"expires_in\": 86400,"
                        + " \"token_type\": \"Bearer\"}", MediaType.APPLICATION_JSON));
        XboxService.Token xsts = new XboxService.Token(Instant.now(), Instant.now(), "xsts",
                new XboxService.DisplayClaims(new XboxService.DisplayClaims.Xui[]{new XboxService.DisplayClaims.Xui("hash", null)}));

        MinecraftService.Token minecraft = service.getMinecraftToken(xsts);

        assertThat(minecraft.accessToken()).isEqualTo("mc");
        assertThat(minecraft.expiresIn()).isEqualTo(86400);
    }

    @Test
    void friendsAreAddedAndRemoved_byNameOrProfileId() {
        friendsUpdate("{\"name\": \"Alex\", \"updateType\": \"ADD\"}");
        friendsUpdate("{\"profileId\": \"p-1\", \"updateType\": \"ADD\"}");
        friendsUpdate("{\"name\": \"Alex\", \"profileId\": \"p-1\", \"updateType\": \"REMOVE\"}");
        friendsUpdate("{\"profileId\": \"p-1\", \"updateType\": \"REMOVE\"}");
        friendsUpdate("{\"profileId\": \"p-2\", \"updateType\": \"REMOVE\"}");
        friendsUpdate("{\"name\": \"Steve\", \"updateType\": \"BLOCK\"}");

        MinecraftService.FriendsList added = service.addFriend(token, "Alex", null);
        service.addFriend("mc-token", null, "p-1");
        service.removeFriend(token, "Alex", "p-1");
        service.removeFriend(token, new MinecraftService.FriendsList.Friend("p-1", "Alex"));
        service.removeFriend("mc-token", new MinecraftService.FriendsList.Friend("p-2", "Bob"));
        service.manageFriends(token, "Steve", null, "BLOCK");

        mojang.verify();
        assertThat(added.friends()).containsExactly(new MinecraftService.FriendsList.Friend("p-1", "Alex"));
        assertThat(added.empty()).isFalse();
    }

    @Test
    void friendsAreListed() {
        mojang.expect(requestTo(API + "/friends")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer mc-token"))
                .andRespond(withSuccess(FRIENDS_JSON, MediaType.APPLICATION_JSON));

        assertThat(service.getFriends(token).friends()).hasSize(1);
    }

    @Test
    void presenceUpdates_returnTheFriendsPresence() {
        mojang.expect(requestTo(API + "/presence")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"status\": \"ONLINE\", \"joinInfo\": {\"value\": null, \"invites\": []}}", true))
                .andRespond(withSuccess(PRESENCE_JSON, MediaType.APPLICATION_JSON));
        mojang.expect(requestTo(API + "/presence"))
                .andExpect(content().json("{\"status\": \"PLAYING_REALMS\", \"joinInfo\": {\"value\": \"r\", \"invites\": [\"x\"]}}", true))
                .andRespond(withSuccess(PRESENCE_JSON, MediaType.APPLICATION_JSON));

        MinecraftService.PresenceList presence = service.updatePresence(token, MinecraftService.PresenceStatus.ONLINE);
        service.updatePresence(token, new MinecraftService.PresenceUpdate(MinecraftService.PresenceStatus.PLAYING_REALMS,
                new MinecraftService.PresenceUpdate.JoinInfo("r", new String[]{"x"})));

        assertThat(presence.presence()).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(MinecraftService.PresenceStatus.PLAYING_SERVER);
            assertThat(p.joinInfo().value()).isEqualTo("srv");
            assertThat(p.lastUpdated()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        });
    }

    @Test
    void profileName_ofTheAuthenticatedAccount() {
        mojang.expect(requestTo(API + "/minecraft/profile")).andExpect(header("Authorization", "Bearer mc-token"))
                .andRespond(withSuccess("{\"id\": \"p-1\", \"name\": \"Alex\"}", MediaType.APPLICATION_JSON));
        mojang.expect(requestTo(API + "/minecraft/profile"))
                .andRespond(withSuccess("{\"id\": \"p-1\"}", MediaType.APPLICATION_JSON));
        mojang.expect(requestTo(API + "/minecraft/profile")).andRespond(withSuccess());

        assertThat(service.getMinecraftProfileName("mc-token")).isEqualTo("Alex");
        assertThat(service.getMinecraftProfileName("mc-token")).isNull();
        assertThat(service.getMinecraftProfileName("mc-token")).isNull();
    }

    @Test
    void profileIds_areNormalizedForComparison() {
        assertThat(MinecraftService.normalizeProfileId("ABCD-ef01")).isEqualTo("abcdef01");
        assertThat(MinecraftService.normalizeProfileId(null)).isNull();
    }
}
