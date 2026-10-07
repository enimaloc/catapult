package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Steam OpenID linking round trip: start, Steam's callback, verification and disconnect. */
class ApiSteamConnectControllerTest {

    private static final String OPENID = "https://steamcommunity.com/openid/login";
    private static final String CLOSE = "http://web.test/connect/steam/close";

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChannelEventPublisher channelEvents = mock(ChannelEventPublisher.class);
    private MockRestServiceServer steam;
    private ApiSteamConnectController controller;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        steam = MockRestServiceServer.bindTo(builder).build();
        controller = new ApiSteamConnectController(accounts, events, channelEvents, builder.build());
        ReflectionTestUtils.setField(controller, "apiBaseUrl", "http://api.test");
        ReflectionTestUtils.setField(controller, "webBaseUrl", "http://web.test");
        account = new UserAccount();
        account.setId(UUID.randomUUID());
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(account.getId().toString()).build();
    }

    /** Starts a link and returns the nonce Steam will hand back. */
    private String start() {
        String redirect = controller.start(jwt()).redirectUrl();
        String returnTo = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("openid.return_to");
        return returnTo.substring(returnTo.indexOf("nonce=") + "nonce=".length());
    }

    private Map<String, String> callback(String nonce, String mode, String claimedId) {
        Map<String, String> params = new HashMap<>();
        if (nonce != null) params.put("nonce", nonce);
        params.put("openid.mode", mode);
        if (claimedId != null) params.put("openid.claimed_id", claimedId);
        return params;
    }

    private void steamSays(String verdict) {
        steam.expect(requestTo(OPENID)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("openid.mode", "check_authentication")))
                .andRespond(withSuccess(verdict, MediaType.TEXT_PLAIN));
    }

    private static String location(ResponseEntity<Void> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        return response.getHeaders().getFirst("Location");
    }

    @Test
    void start_redirectsToSteamWithAReturnUrlCarryingANonce() {
        String redirect = controller.start(jwt()).redirectUrl();

        var query = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams();
        assertThat(redirect).startsWith(OPENID + "?");
        assertThat(query.getFirst("openid.mode")).isEqualTo("checkid_setup");
        assertThat(query.getFirst("openid.realm")).isEqualTo("http://api.test");
        assertThat(query.getFirst("openid.return_to")).startsWith("http://api.test/api/connect/steam/callback?nonce=");
    }

    @Test
    void verifiedCallback_linksTheSteamAccount() {
        String nonce = start();
        steamSays("ns:http://specs.openid.net/auth/2.0\nis_valid:true\n");

        ResponseEntity<Void> response = controller.callback(
                callback(nonce, "id_res", "https://steamcommunity.com/openid/id/76561198000000000"));

        assertThat(location(response)).isEqualTo(CLOSE);
        assertThat(account.getSteamId()).isEqualTo("76561198000000000");
        verify(accounts).save(account);
        verify(events).publishEvent(any(SteamLinkedEvent.class));
        verify(channelEvents).connectionChanged(account.getId(), new ProviderConnectionsDto("STEAM", true, null));
    }

    @Test
    void nonces_areSingleUse_andRequired() {
        String nonce = start();
        steamSays("is_valid:true");
        controller.callback(callback(nonce, "id_res", "https://steamcommunity.com/openid/id/1"));

        assertThat(location(controller.callback(callback(nonce, "id_res", "x")))).isEqualTo(CLOSE + "?error=nonce");
        assertThat(location(controller.callback(callback(null, "id_res", "x")))).isEqualTo(CLOSE + "?error=nonce");
    }

    @Test
    void cancelledLogins_areRejected() {
        assertThat(location(controller.callback(callback(start(), "cancel", null)))).isEqualTo(CLOSE + "?error=rejected");
        verify(accounts, never()).save(any());
    }

    @Test
    void unverifiedAssertions_areRejected() {
        steamSays("is_valid:false");
        steam.expect(requestTo(OPENID)).andRespond(withServerError());

        assertThat(location(controller.callback(callback(start(), "id_res", "https://steamcommunity.com/openid/id/1"))))
                .isEqualTo(CLOSE + "?error=verify");
        assertThat(location(controller.callback(callback(start(), "id_res", "https://steamcommunity.com/openid/id/1"))))
                .isEqualTo(CLOSE + "?error=verify");
        verify(accounts, never()).save(any());
    }

    @Test
    void claimedIdsOutsideSteam_areRejected() {
        steamSays("is_valid:true");
        steamSays("is_valid:true");

        assertThat(location(controller.callback(callback(start(), "id_res", "https://evil.example/id/1"))))
                .isEqualTo(CLOSE + "?error=invalid");
        assertThat(location(controller.callback(callback(start(), "id_res", null))))
                .isEqualTo(CLOSE + "?error=invalid");
    }

    @Test
    void callbackForADeletedAccount_is404() {
        String nonce = start();
        when(accounts.findById(account.getId())).thenReturn(Optional.empty());
        steamSays("is_valid:true");

        assertThatThrownBy(() -> controller.callback(callback(nonce, "id_res", "https://steamcommunity.com/openid/id/1")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void disconnect_unlinksSteam() {
        account.setSteamId("1");

        controller.disconnect(jwt());

        assertThat(account.getSteamId()).isNull();
        verify(accounts).save(account);
        verify(channelEvents).connectionChanged(account.getId(), new ProviderConnectionsDto("STEAM", false, null));
    }
}
