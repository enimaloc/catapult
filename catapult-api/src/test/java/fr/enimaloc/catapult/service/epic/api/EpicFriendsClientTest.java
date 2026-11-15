package fr.enimaloc.catapult.service.epic.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EpicFriendsClientTest {

    private static final String URL = "https://friends.test/api/v1";

    private MockRestServiceServer server;
    private EpicFriendsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EpicFriendsClient(builder, URL);
    }

    @Test
    void add_204IsSent_409DuplicateIsAlreadyFriends() {
        server.expect(requestTo(URL + "/bot/friends/f1")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok")).andRespond(withNoContent());
        server.expect(requestTo(URL + "/bot/friends/f2")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errorCode\":\"errors.com.epicgames.friends.duplicate_friendship\"}"));

        assertThat(client.add("tok", "bot", "f1")).isEqualTo(EpicFriendsClient.AddResult.SENT);
        assertThat(client.add("tok", "bot", "f2")).isEqualTo(EpicFriendsClient.AddResult.ALREADY_FRIENDS);
        server.verify();
    }

    @Test
    void add_otherConflictsAndFailuresThrow() {
        server.expect(requestTo(URL + "/bot/friends/f1")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON).body("{\"errorCode\":\"errors.com.epicgames.friends.other\"}"));

        assertThatThrownBy(() -> client.add("tok", "bot", "f1"))
                .isInstanceOf(org.springframework.web.client.RestClientException.class);
    }

    @Test
    void delete_ignores404() {
        server.expect(requestTo(URL + "/bot/friends/f1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        server.expect(requestTo(URL + "/bot/friends/f2")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.delete("tok", "bot", "f1");
        client.delete("tok", "bot", "f2");
        server.verify();
    }

    @Test
    void summary_mapsFriendsIncomingOutgoing() {
        server.expect(requestTo(URL + "/bot/summary")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("""
                        {"friends":[{"accountId":"a","alias":""},{"accountId":"b"}],
                         "incoming":[{"accountId":"c"}],"outgoing":[{"accountId":"d"}],"blocklist":[]}
                        """, MediaType.APPLICATION_JSON));

        EpicFriendsClient.Summary summary = client.summary("tok", "bot");

        assertThat(summary.friends()).isEqualTo(Set.of("a", "b"));
        assertThat(summary.incoming()).isEqualTo(Set.of("c"));
        assertThat(summary.outgoing()).isEqualTo(Set.of("d"));
    }

    @Test
    void unauthorized_isRefreshRefused() {
        server.expect(requestTo(URL + "/bot/summary")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.summary("tok", "bot")).isInstanceOf(EpicAuthException.class)
                .hasMessageNotContaining("tok");
    }
}
