package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.GameStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean GameStateService gameStateService;
    @MockitoBean ActivityLogService activityLogService;
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
        userAccount.setBotEnabled(true);

        var oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        var catapultUser = new CatapultOAuth2User(oAuth2User, userAccount, false);

        auth = new UsernamePasswordAuthenticationToken(
            catapultUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(gameStateService.getLastKnownGame(userAccount)).thenReturn(Optional.empty());
    }

    @Test
    void getDashboard_rendersPage() throws Exception {
        mockMvc.perform(get("/dashboard").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard"))
            .andExpect(model().attributeExists("user", "botEnabled", "isPendingDeletion"));
    }

    @Test
    void getDashboard_withCurrentGame_exposesGame() throws Exception {
        var game = new DetectedGame("12345", GameBinding.SourceType.STEAM, "Fortnite");
        when(gameStateService.getLastKnownGame(userAccount)).thenReturn(Optional.of(game));

        mockMvc.perform(get("/dashboard").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(model().attribute("currentGame", game));
    }

    @Test
    void getDashboard_pendingDeletion_flagSet() throws Exception {
        userAccount.setStatus(UserAccount.Status.PENDING_DELETION);

        mockMvc.perform(get("/dashboard").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(model().attribute("isPendingDeletion", true));
    }

    @Test
    void getLogs_returnsSseStream() throws Exception {
        when(activityLogService.subscribe(userAccount.getId())).thenReturn(new SseEmitter());

        mockMvc.perform(get("/dashboard/logs").with(authentication(auth)))
            .andExpect(status().isOk());
    }
}
