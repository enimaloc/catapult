package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SteamAuthController.class)
class SteamAuthControllerTest {

    @TestConfiguration
    static class TestRestClientConfig {
        @Bean
        RestClient restClient() {
            return mock(RestClient.class, Mockito.RETURNS_DEEP_STUBS);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired RestClient restClient;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ApplicationEventPublisher eventPublisher;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean AdminCclService adminCclService;
    @MockitoBean ExperimentService experimentService;

    private UserAccount userAccount;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setup() {
        userAccount = new UserAccount();
        userAccount.setTwitchId("twitch-123");
        userAccount.setStatus(UserAccount.Status.ACTIVE);

        var oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        var catapultUser = new CatapultOAuth2User(oAuth2User, userAccount, false);

        auth = new UsernamePasswordAuthenticationToken(
            catapultUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @SuppressWarnings("unchecked")
    private void setupSteamVerification(boolean valid) {
        when(restClient.post().uri(anyString()).contentType(any()).body(any()).retrieve().body(String.class))
            .thenReturn(valid ? "is_valid:true\n" : "is_valid:false\n");
    }

    @Test
    void getConnect_redirectsToSteamOpenId() throws Exception {
        mockMvc.perform(get("/connect/steam").with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("steamcommunity.com/openid/login")));
    }

    @Test
    void getCallback_nonceMismatch_redirectsToSettingsError() throws Exception {
        mockMvc.perform(get("/connect/steam/callback")
                .with(authentication(auth))
                .sessionAttr("steam_link_nonce", "correct-nonce")
                .param("nonce", "wrong-nonce")
                .param("openid.mode", "id_res"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(view().name("closing"));
    }

    @Test
    void getCallback_wrongMode_redirectsToSettingsError() throws Exception {
        mockMvc.perform(get("/connect/steam/callback")
                .with(authentication(auth))
                .sessionAttr("steam_link_nonce", "test-nonce")
                .param("nonce", "test-nonce")
                .param("openid.mode", "cancel"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(view().name("closing"));
    }

    @Test
    void getCallback_steamVerificationFails_redirectsToSettingsError() throws Exception {
        setupSteamVerification(false);

        mockMvc.perform(get("/connect/steam/callback")
                .with(authentication(auth))
                .sessionAttr("steam_link_nonce", "test-nonce")
                .param("nonce", "test-nonce")
                .param("openid.mode", "id_res"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(view().name("closing"));
    }

    @Test
    void getCallback_invalidClaimedId_redirectsToSettingsError() throws Exception {
        setupSteamVerification(true);

        mockMvc.perform(get("/connect/steam/callback")
                .with(authentication(auth))
                .sessionAttr("steam_link_nonce", "test-nonce")
                .param("nonce", "test-nonce")
                .param("openid.mode", "id_res")
                .param("openid.claimed_id", "https://evil.com/id/123"))
            .andExpect(status().is2xxSuccessful())
            .andExpect(view().name("closing"));
    }

    @Test
    void postDisconnect_clearsSteamIdAndRedirects() throws Exception {
        userAccount.setSteamId("76561198012345678");

        mockMvc.perform(post("/connect/steam/disconnect")
                .with(csrf()).with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/settings"));

        verify(userAccountRepository).save(argThat(u -> u.getSteamId() == null));
    }
}
