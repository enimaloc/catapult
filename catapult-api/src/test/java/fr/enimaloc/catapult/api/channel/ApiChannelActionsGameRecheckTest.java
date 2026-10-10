package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsGameRecheckTest extends ApiChannelActionsTestSupport {

    @Test
    void recheckGame_ownerWithActiveAccount_triggersManualCheck() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");
        user.setStatus(UserAccount.Status.ACTIVE);
        user.setBotEnabled(false);

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/game/recheck")
                        .with(userJwt(userId))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(schedulerService).triggerManualCheck(user);
    }

    @Test
    void recheckGame_accountInactive_conflict() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");
        user.setStatus(UserAccount.Status.INACTIVE);

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/game/recheck")
                        .with(userJwt(userId))
                        .with(csrf()))
                .andExpect(status().isConflict());

        verify(schedulerService, never()).triggerManualCheck(user);
    }

    @Test
    void recheckGame_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UUID channelId = UUID.randomUUID();
        UserAccount channelUser = new UserAccount();
        channelUser.setId(channelId);
        channelUser.setTwitchUsername("streamer");
        channelUser.setStatus(UserAccount.Status.ACTIVE);

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/game/recheck")
                        .with(userJwt(viewerId))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(schedulerService, never()).triggerManualCheck(channelUser);
    }
}
