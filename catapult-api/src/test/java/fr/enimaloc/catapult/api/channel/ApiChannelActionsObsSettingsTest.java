package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsObsSettingsTest extends ApiChannelActionsTestSupport {

    private static final String BODY = "{\"enabled\":true,\"host\":\"127.0.0.1\",\"port\":4455,\"password\":\"pw\"}";

    @Test
    void saveObsSettings_owner_delegatesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/obs")
                        .with(userJwt(userId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        verify(twitchatWidgetSettingsService).updateSettings(user, true, "127.0.0.1", 4455, "pw");
    }

    @Test
    void saveObsSettings_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UserAccount channelUser = new UserAccount();
        channelUser.setId(UUID.randomUUID());
        channelUser.setTwitchUsername("streamer");

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/obs")
                        .with(userJwt(viewerId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }
}
