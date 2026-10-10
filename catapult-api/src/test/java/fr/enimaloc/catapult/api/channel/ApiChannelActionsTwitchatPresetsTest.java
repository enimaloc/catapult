package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadPreset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsTwitchatPresetsTest extends ApiChannelActionsTestSupport {

    private UserAccount stubOwner(UUID userId, String username) {
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername(username);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername(username)).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);
        return user;
    }

    @Test
    void listPresets_owner_returnsMappedPresets() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId, "streamer");

        TwitchatPayloadPreset preset = new TwitchatPayloadPreset();
        preset.setId(UUID.randomUUID());
        preset.setUser(user);
        preset.setEventType(TwitchatNotificationEventType.STREAM_STARTED);
        preset.setName("Discret");
        preset.setPayloadJson("{\"message\":\"Live.\"}");
        preset.setCreatedAt(Instant.now());
        preset.setUpdatedAt(Instant.now());
        when(twitchatPayloadPresetService.listPresets(user)).thenReturn(List.of(preset));

        mvc.perform(get("/api/channels/streamer/twitchat/presets").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Discret"))
                .andExpect(jsonPath("$[0].eventType").value("STREAM_STARTED"));
    }

    @Test
    void createPreset_owner_delegatesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId, "streamer");
        TwitchatPayloadPreset created = new TwitchatPayloadPreset();
        created.setId(UUID.randomUUID());
        created.setUser(user);
        created.setEventType(TwitchatNotificationEventType.STREAM_STARTED);
        created.setName("Discret");
        created.setPayloadJson("{\"message\":\"Live.\"}");
        when(twitchatPayloadPresetService.createPreset(user, TwitchatNotificationEventType.STREAM_STARTED,
                "Discret", "{\"message\":\"Live.\"}")).thenReturn(created);

        mvc.perform(post("/api/channels/streamer/twitchat/presets")
                        .with(userJwt(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"STREAM_STARTED\",\"name\":\"Discret\",\"payloadJson\":\"{\\\"message\\\":\\\"Live.\\\"}\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Discret"));
    }

    @Test
    void deletePreset_owner_delegatesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        stubOwner(userId, "streamer");
        UUID presetId = UUID.randomUUID();

        mvc.perform(delete("/api/channels/streamer/twitchat/presets/{id}", presetId)
                        .with(userJwt(userId)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void setActivePreset_owner_delegatesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId, "streamer");
        UUID presetId = UUID.randomUUID();

        mvc.perform(put("/api/channels/streamer/twitchat/active-presets/{eventType}", "STREAM_STARTED")
                        .with(userJwt(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presetId\":\"" + presetId + "\"}"))
                .andExpect(status().isNoContent());

        verify(twitchatPayloadPresetService).setActivePreset(user, TwitchatNotificationEventType.STREAM_STARTED, presetId);
    }

    @Test
    void testTwitchatPreset_owner_delegatesToNotifier() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId, "streamer");

        mvc.perform(post("/api/channels/streamer/twitchat/presets/test")
                        .with(userJwt(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"STREAM_STARTED\",\"payloadJson\":\"{\\\"message\\\":\\\"Hi\\\"}\"}"))
                .andExpect(status().isNoContent());

        verify(twitchatNotifier).sendTestNotification(user, TwitchatNotificationEventType.STREAM_STARTED,
                "{\"message\":\"Hi\"}");
    }

    @Test
    void listPresets_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);
        UserAccount channelUser = new UserAccount();
        channelUser.setId(UUID.randomUUID());
        channelUser.setTwitchUsername("streamer");
        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(get("/api/channels/streamer/twitchat/presets").with(userJwt(viewerId)))
                .andExpect(status().isForbidden());
    }
}
