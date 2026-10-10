package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsTwitchatBranchTest extends ApiChannelActionsTestSupport {

    private UserAccount owner(UUID userId) {
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);
        return user;
    }

    @Test
    void saveTwitchatBranch_owner_delegatesToService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = owner(userId);

        mvc.perform(post("/api/channels/streamer/settings/twitchat/branch")
                        .with(userJwt(userId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"beta\"}"))
                .andExpect(status().isNoContent());

        verify(twitchatWidgetSettingsService).updateBranch(user, "beta");
    }

    @Test
    void saveTwitchatBranch_unknownBranch_badRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = owner(userId);
        when(twitchatWidgetSettingsService.updateBranch(user, "main"))
                .thenThrow(new IllegalArgumentException("Unknown Twitchat branch main"));

        mvc.perform(post("/api/channels/streamer/settings/twitchat/branch")
                        .with(userJwt(userId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"main\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveTwitchatBranch_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UserAccount channelUser = new UserAccount();
        channelUser.setId(UUID.randomUUID());
        channelUser.setTwitchUsername("streamer");

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/twitchat/branch")
                        .with(userJwt(viewerId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"beta\"}"))
                .andExpect(status().isForbidden());

        verify(twitchatWidgetSettingsService, never()).updateBranch(any(), any());
    }
}
