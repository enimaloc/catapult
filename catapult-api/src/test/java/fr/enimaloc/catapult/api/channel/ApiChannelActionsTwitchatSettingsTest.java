package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsTwitchatSettingsTest extends ApiChannelActionsTestSupport {

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
