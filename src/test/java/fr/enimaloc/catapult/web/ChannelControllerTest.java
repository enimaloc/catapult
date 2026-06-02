package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.ConnectionEventService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.EventSubService;
import fr.enimaloc.catapult.service.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@WebMvcTest(ChannelController.class)
class ChannelControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ChannelAccessService channelAccessService;
    @MockitoBean GameBindingRepository gameBindingRepository;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean StreamStateService streamStateService;
    @MockitoBean AdminCclService adminCclService;
    @MockitoBean BindingService bindingService;
    @MockitoBean TwitchService twitchService;
    @MockitoBean AccountService accountService;
    @MockitoBean ActivityLogService activityLogService;
    @MockitoBean ConnectionEventService connectionEventService;
    @MockitoBean EventSubService twitchEventSubService;
    @MockitoBean UserSettingsRepository userSettingsRepository;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean ExperimentService experimentService;

    private UserAccount owner;
    private UserAccount moderator;
    private UsernamePasswordAuthenticationToken ownerAuth;
    private UsernamePasswordAuthenticationToken modAuth;

    @BeforeEach
    void setup() {
        owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        owner.setTwitchId("owner-twitch-id");
        owner.setTwitchUsername("streamer");
        owner.setStatus(UserAccount.Status.ACTIVE);

        moderator = new UserAccount();
        moderator.setId(UUID.randomUUID());
        moderator.setTwitchId("mod-twitch-id");
        moderator.setTwitchUsername("moderator");
        moderator.setStatus(UserAccount.Status.ACTIVE);

        ownerAuth = authFor(owner);
        modAuth = authFor(moderator);

        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(owner));
        when(gameBindingRepository.findByUser(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(gameStateService.getLastKnownGame(any())).thenReturn(Optional.empty());
        when(adminCclService.getAllCcls()).thenReturn(List.of());
        when(experimentService.getActiveAssignments(any())).thenReturn(List.of());
        when(userSettingsRepository.findById(any())).thenReturn(Optional.of(new UserSettings()));
    }

    @Test
    void owner_gets_200_with_isOwner_true() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer").with(authentication(ownerAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("app"))
            .andExpect(model().attribute("isOwner", true));
    }

    @Test
    void moderator_gets_200_with_isOwner_false() throws Exception {
        when(channelAccessService.canAccess(moderator, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer").with(authentication(modAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("app"))
            .andExpect(model().attribute("isOwner", false));
    }

    @Test
    void unknown_channel_returns_404() throws Exception {
        when(userAccountRepository.findByTwitchUsername("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/channels/ghost").with(authentication(ownerAuth)))
            .andExpect(status().isNotFound());
    }

    @Test
    void unauthorized_viewer_returns_403() throws Exception {
        when(channelAccessService.canAccess(moderator, owner)).thenReturn(false);

        mockMvc.perform(get("/channels/streamer").with(authentication(modAuth)))
            .andExpect(status().isForbidden());
    }

    @Test
    void fragmentIncompleteFallbackSettings_returns200() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer/fragments/incomplete-fallback-settings")
                .with(authentication(ownerAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("fragments/incomplete-fallback-settings :: incomplete-fallback-settings"));
    }

    @Test
    void saveIncompleteFallbackSettings_redirectsOnSuccess() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);
        when(userSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(new UserSettings()));

        mockMvc.perform(post("/channels/streamer/settings/incomplete-fallback")
                .with(authentication(ownerAuth))
                .with(csrf())
                .param("twitchGameId", "509658")
                .param("twitchGameName", "Just Chatting"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));

        verify(userSettingsRepository).save(any(UserSettings.class));
    }

    @Test
    void fragmentStatus_returns200() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer/fragments/status")
                .with(authentication(ownerAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("fragments/status :: status"));
    }

    @Test
    void fragmentBindings_returns200() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer/fragments/bindings")
                .with(authentication(ownerAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("fragments/bindings :: bindings"));
    }

    @Test
    void fragmentConnections_returns200() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer/fragments/connections")
                .with(authentication(ownerAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("fragments/connections :: connections"));
    }

    @Test
    void fragmentNoGameSettings_returns200() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer/fragments/no-game-settings")
                .with(authentication(ownerAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("fragments/no-game-settings :: no-game-settings"));
    }

    @Test
    void updateBinding_redirectsToChannel() throws Exception {
        UUID id = UUID.randomUUID();
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(post("/channels/streamer/bindings/" + id)
                .with(authentication(ownerAuth))
                .with(csrf())
                .param("twitchGameId", "game-123")
                .param("twitchGameName", "My Game"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));
    }

    @Test
    void toggleCclEnabled_redirectsToChannel() throws Exception {
        UUID id = UUID.randomUUID();
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(post("/channels/streamer/bindings/" + id + "/ccl-toggle")
                .with(authentication(ownerAuth))
                .with(csrf())
                .param("enabled", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));
    }

    @Test
    void toggleIgnored_redirectsToChannel() throws Exception {
        UUID id = UUID.randomUUID();
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(post("/channels/streamer/bindings/" + id + "/ignored-toggle")
                .with(authentication(ownerAuth))
                .with(csrf())
                .param("ignored", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));
    }

    @Test
    void deleteBinding_redirectsToChannel() throws Exception {
        UUID id = UUID.randomUUID();
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(post("/channels/streamer/bindings/" + id + "/delete")
                .with(authentication(ownerAuth))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));
    }

    @Test
    void toggleBot_redirectsToChannel() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(post("/channels/streamer/settings/bot")
                .with(authentication(ownerAuth))
                .with(csrf())
                .param("enabled", "false"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));
    }

    @Test
    void saveNoGameSettings_redirectsToChannel() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);
        when(userSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(new UserSettings()));

        mockMvc.perform(post("/channels/streamer/settings/no-game")
                .with(authentication(ownerAuth))
                .with(csrf())
                .param("twitchGameId", "509658")
                .param("twitchGameName", "Just Chatting"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));
    }

    @Test
    void saveNoGameSettings_persistsTriggerFlags() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);
        UserSettings settings = new UserSettings();
        when(userSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(settings));

        mockMvc.perform(post("/channels/streamer/settings/no-game")
                .with(authentication(ownerAuth))
                .with(csrf())
                .param("twitchGameId", "509658")
                .param("twitchGameName", "Just Chatting")
                .param("applyOnStreamStart", "true")
                // applyOnNoGame intentionally absent (unchecked checkbox → false)
                .param("applyOnStreamEnd", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/streamer"));

        ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
        verify(userSettingsRepository).save(captor.capture());
        UserSettings saved = captor.getValue();
        assertThat(saved.isApplyDefaultOnStreamStart()).isTrue();
        assertThat(saved.isApplyDefaultOnNoGame()).isFalse();
        assertThat(saved.isApplyDefaultOnStreamEnd()).isTrue();
    }

    private UsernamePasswordAuthenticationToken authFor(UserAccount user) {
        var oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        var catapultUser = new CatapultOAuth2User(oAuth2User, user, false);
        return new UsernamePasswordAuthenticationToken(
            catapultUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}