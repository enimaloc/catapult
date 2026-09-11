package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.BotToggleService;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.SchedulerService;
import fr.enimaloc.catapult.service.TwitchService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import fr.enimaloc.catapult.service.notification.TwitchatPayloadPresetService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChannelActionsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiChannelActionsTwitchatSettingsTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean UserSettingsRepository userSettingsRepository;
    @MockitoBean ChannelAccessService channelAccessService;
    @MockitoBean BindingService bindingService;
    @MockitoBean BotToggleService botToggleService;
    @MockitoBean TwitchService twitchService;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean AccountService accountService;
    @MockitoBean TokenEncryptionService tokenEncryptionService;
    @MockitoBean SteamApiKeyRepository steamApiKeyRepository;
    @MockitoBean ChannelEventPublisher channelEventPublisher;
    @MockitoBean SchedulerService schedulerService;
    @MockitoBean TwitchatWidgetSettingsService twitchatWidgetSettingsService;
    @MockitoBean TwitchatPayloadPresetService twitchatPayloadPresetService;
    @MockitoBean TwitchatNotifier twitchatNotifier;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("twitchId", "123"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static TwitchatWidgetSettings settingsFor(UserAccount user, UUID widgetToken) {
        user.setWidgetToken(widgetToken);
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(user);
        settings.setEnabled(true);
        settings.setObsHost("127.0.0.1");
        settings.setObsPort(4455);
        settings.setObsPasswordEncrypted("ENC(pw)");
        return settings;
    }

    @Test
    void getTwitchatSettings_owner_returnsSettings() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");

        UUID widgetToken = UUID.randomUUID();
        TwitchatWidgetSettings settings = settingsFor(user, widgetToken);

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);
        when(twitchatWidgetSettingsService.getOrCreate(user)).thenReturn(settings);

        mvc.perform(get("/api/channels/streamer/settings/twitchat")
                        .with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.obsHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.obsPort").value(4455))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.widgetToken").value(widgetToken.toString()));
    }

    @Test
    void getTwitchatSettings_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UUID channelId = UUID.randomUUID();
        UserAccount channelUser = new UserAccount();
        channelUser.setId(channelId);
        channelUser.setTwitchUsername("streamer");

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(get("/api/channels/streamer/settings/twitchat")
                        .with(userJwt(viewerId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void saveTwitchatSettings_owner_delegatesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/twitchat")
                        .with(userJwt(userId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"obsHost\":\"127.0.0.1\",\"obsPort\":4455,\"obsPassword\":\"pw\"}"))
                .andExpect(status().isNoContent());

        verify(twitchatWidgetSettingsService).updateSettings(user, true, "127.0.0.1", 4455, "pw");
    }

    @Test
    void saveTwitchatSettings_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UUID channelId = UUID.randomUUID();
        UserAccount channelUser = new UserAccount();
        channelUser.setId(channelId);
        channelUser.setTwitchUsername("streamer");

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/twitchat")
                        .with(userJwt(viewerId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"obsHost\":\"127.0.0.1\",\"obsPort\":4455,\"obsPassword\":\"pw\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void regenerateTwitchatToken_owner_delegatesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");

        UUID newToken = UUID.randomUUID();
        TwitchatWidgetSettings settings = settingsFor(user, newToken);

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);
        when(twitchatWidgetSettingsService.regenerateToken(user)).thenReturn(settings);

        mvc.perform(post("/api/channels/streamer/settings/twitchat/regenerate")
                        .with(userJwt(userId))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widgetToken").value(newToken.toString()));
    }

    @Test
    void regenerateTwitchatToken_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UUID channelId = UUID.randomUUID();
        UserAccount channelUser = new UserAccount();
        channelUser.setId(channelId);
        channelUser.setTwitchUsername("streamer");

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/twitchat/regenerate")
                        .with(userJwt(viewerId))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
