package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.common.dto.emergency.ChannelEmergencyDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsEmergencyTest extends ApiChannelActionsTestSupport {

    @Test
    void emergency_ownerReadsTheChannelView() throws Exception {
        UserAccount owner = registered("streamer");
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);
        when(emergencyViews.channel(owner)).thenReturn(new ChannelEmergencyDto(true, List.of(), List.of()));

        mvc.perform(get("/api/internal/users/{userId}/emergency", owner.getId()).with(userJwt(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botEnabled").value(true));
    }

    @Test
    void resume_ownerLiftsTheChannelStops() throws Exception {
        UserAccount owner = registered("streamer");
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);
        when(emergencyStopService.liftChannel(owner, "streamer")).thenReturn(true);

        mvc.perform(post("/api/internal/users/{userId}/emergency/resume", owner.getId())
                        .with(userJwt(owner.getId())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifted").value(true));

        verify(emergencyStopService).liftChannel(owner, "streamer");
    }

    @Test
    void emergency_nonOwner_forbidden() throws Exception {
        UserAccount viewer = registered("viewer");
        UserAccount channel = registered("streamer");
        when(channelAccessService.canAccess(viewer, channel)).thenReturn(true);

        mvc.perform(get("/api/internal/users/{userId}/emergency", channel.getId()).with(userJwt(viewer.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/users/{userId}/emergency/resume", channel.getId())
                        .with(userJwt(viewer.getId())).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
