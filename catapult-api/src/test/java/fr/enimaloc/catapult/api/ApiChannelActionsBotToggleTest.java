package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsBotToggleTest extends ApiChannelActionsTestSupport {

    @Test
    void toggleBot_ownerTogglesOwnBot_delegatesToBotToggleService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");
        user.setBotEnabled(true);

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/bot")
                        .with(userJwt(userId))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(botToggleService).setBotEnabled(user, false);
    }

    @Test
    void toggleBot_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UUID channelId = UUID.randomUUID();
        UserAccount channelUser = new UserAccount();
        channelUser.setId(channelId);
        channelUser.setTwitchUsername("streamer");
        channelUser.setBotEnabled(true);

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/bot")
                        .with(userJwt(viewerId))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
