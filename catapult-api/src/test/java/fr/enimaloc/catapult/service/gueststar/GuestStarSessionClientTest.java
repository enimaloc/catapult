package fr.enimaloc.catapult.service.gueststar;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GuestStarSessionClientTest {

    private static final String SESSION = "https://api.twitch.tv/helix/guest_star/session?broadcaster_id=42&moderator_id=42";

    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final TwitchTokenService tokenService = mock(TwitchTokenService.class);
    private MockRestServiceServer helix;
    private GuestStarSessionClient client;
    private final UserAccount user = new UserAccount();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        helix = MockRestServiceServer.bindTo(builder).build();
        client = new GuestStarSessionClient(builder.build(), new ObjectMapper(), tokens, tokenService);
        ReflectionTestUtils.setField(client, "twitchClientId", "client-id");
        user.setId(UUID.randomUUID());
        user.setTwitchId("42");
        OAuthToken token = new OAuthToken();
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
        when(tokenService.resolveAccessToken(token, user)).thenReturn("streamer-token");
    }

    @Test
    void liveGuestsWithNames() {
        helix.expect(requestTo(SESSION)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer streamer-token"))
                .andExpect(header("Client-ID", "client-id"))
                .andRespond(withSuccess("""
                        {"data": [{"id": "s-1", "guests": [
                          {"slot_id": "0", "user_id": "42", "is_live": true},
                          {"slot_id": "2", "user_id": "20", "is_live": true},
                          {"slot_id": "1", "user_id": "10", "is_live": false}]}]}""", MediaType.APPLICATION_JSON));
        helix.expect(requestTo("https://api.twitch.tv/helix/users?id=42&id=20"))
                .andRespond(withSuccess("""
                        {"data": [{"id": "42", "display_name": "Host"}, {"id": "20", "display_name": "Bob"}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.liveGuests(user)).containsExactly(
                new GuestStarRoster.Guest("0", "42", "Host"), new GuestStarRoster.Guest("2", "20", "Bob"));
        helix.verify();
    }

    @Test
    void noSessionGivesEmpty() {
        helix.expect(requestTo(SESSION)).andRespond(withSuccess("{\"data\": []}", MediaType.APPLICATION_JSON));
        assertThat(client.liveGuests(user)).isEmpty();
    }

    @Test
    void forbiddenGivesEmpty() {
        helix.expect(requestTo(SESSION)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(client.liveGuests(user)).isEmpty();
    }

    @Test
    void noTokenGivesEmpty() {
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());
        assertThat(client.liveGuests(user)).isEmpty();
    }
}
