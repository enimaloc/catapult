package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean OAuthTokenRepository oAuthTokenRepository;
    @MockitoBean GetterConfigRepository getterConfigRepository;
    @MockitoBean UserSettingsRepository userSettingsRepository;
    @MockitoBean AccountService accountService;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean AdminCclService adminCclService;
    @MockitoBean ExperimentService experimentService;

    private UserAccount userAccount;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setup() {
        userAccount = new UserAccount();
        userAccount.setId(java.util.UUID.randomUUID());
        userAccount.setTwitchId("twitch-123");
        userAccount.setTwitchUsername("testuser");
        userAccount.setStatus(UserAccount.Status.ACTIVE);

        var oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        var catapultUser = new CatapultOAuth2User(oAuth2User, userAccount, false);

        auth = new UsernamePasswordAuthenticationToken(
            catapultUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(getterConfigRepository.findByUserOrderByPriorityAsc(userAccount)).thenReturn(List.of());
        when(userSettingsRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void getSettings_rendersPage() throws Exception {
        mockMvc.perform(get("/settings").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(view().name("settings"))
            .andExpect(model().attributeExists("user", "hasSteamProvider", "hasSteam",
                "getterConfigs", "isPendingDeletion"));
    }

    @Test
    void getSettings_pendingDeletion_flagSet() throws Exception {
        userAccount.setStatus(UserAccount.Status.PENDING_DELETION);

        mockMvc.perform(get("/settings").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(model().attribute("isPendingDeletion", true));
    }

    @Test
    void postToggleBot_redirectsToSettings() throws Exception {
        mockMvc.perform(post("/settings/bot")
                .with(csrf()).with(authentication(auth))
                .param("enabled", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/settings"));
    }

    @Test
    void postDeleteAccount_matchingUsername_initiatesDeletion() throws Exception {
        mockMvc.perform(post("/settings/delete-account")
                .with(csrf()).with(authentication(auth))
                .param("confirmUsername", "testuser"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/settings"));

        verify(accountService).initiateAccountDeletion(userAccount);
    }

    @Test
    void postDeleteAccount_wrongUsername_doesNotInitiateDeletion() throws Exception {
        mockMvc.perform(post("/settings/delete-account")
                .with(csrf()).with(authentication(auth))
                .param("confirmUsername", "wrongname"))
            .andExpect(status().is3xxRedirection());

        verify(accountService, never()).initiateAccountDeletion(any());
    }

    @Test
    void postCancelDeletion_redirectsToSettings() throws Exception {
        mockMvc.perform(post("/settings/cancel-deletion")
                .with(csrf()).with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/settings"));

        verify(accountService).cancelAccountDeletion(userAccount);
    }

    @Test
    void postDisconnectProvider_redirectsToSettings() throws Exception {
        mockMvc.perform(post("/settings/disconnect")
                .with(csrf()).with(authentication(auth))
                .param("provider", "TWITCH"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/settings"));

        verify(accountService).disconnectProvider(userAccount, OAuthToken.Provider.TWITCH);
    }
}
