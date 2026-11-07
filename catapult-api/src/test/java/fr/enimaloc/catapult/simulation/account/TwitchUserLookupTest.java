package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The panel's prefill reads a real Twitch user by login, with the app token. */
class TwitchUserLookupTest {

    private static final String USERS = "https://api.twitch.tv/helix/users?login=";

    private final TwitchTokenService tokens = mock(TwitchTokenService.class);
    private MockRestServiceServer helix;
    private TwitchUserLookup lookup;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        helix = MockRestServiceServer.bindTo(builder).build();
        lookup = new TwitchUserLookup(builder.build(), tokens, "client-id");
        when(tokens.getAppAccessToken()).thenReturn("app-token");
    }

    private void respond(String login, String body) {
        helix.expect(requestTo(USERS + login)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andExpect(header("Client-Id", "client-id"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void returns_the_id_login_and_avatar() {
        respond("alice", "{\"data\":[{\"id\":\"123\",\"login\":\"alice\",\"display_name\":\"Alice\","
                + "\"profile_image_url\":\"https://cdn/a.png\"}]}");

        assertThat(lookup.byLogin(" Alice ")).contains(
                new TwitchUserLookup.TwitchUser("123", "alice", "https://cdn/a.png"));
    }

    @Test
    void an_unknown_login_is_empty() {
        respond("nobody", "{\"data\":[]}");

        assertThat(lookup.byLogin("nobody")).isEmpty();
    }

    @Test
    void a_blank_login_is_refused() {
        assertThatThrownBy(() -> lookup.byLogin(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_twitch_error_is_an_illegal_state() {
        helix.expect(requestTo(USERS + "alice")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> lookup.byLogin("alice")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Twitch lookup failed");
    }
}
