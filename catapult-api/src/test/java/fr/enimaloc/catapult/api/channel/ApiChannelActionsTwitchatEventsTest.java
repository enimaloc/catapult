package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadMode;
import fr.enimaloc.catapult.service.notification.TwitchatValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiChannelActionsTwitchatEventsTest extends ApiChannelActionsTestSupport {

    private static final String BODY = "[{\"action\":\"SEND_MESSAGE\",\"mode\":\"FORM\",\"dataJson\":\"{\\\"message\\\":\\\"hi\\\"}\",\"enabled\":true}]";
    private static final List<TwitchatEventActionDto> PAYLOADS =
            List.of(new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{\"message\":\"hi\"}", true));

    private UserAccount stubOwner(UUID userId) {
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);
        return user;
    }

    @Test
    void list_returnsEveryEventWithItsPayloads() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId);
        TwitchatEventAction action = new TwitchatEventAction();
        action.setAction("SEND_MESSAGE");
        action.setMode(TwitchatPayloadMode.RAW);
        action.setDataJson("{}");
        action.setEnabled(false);
        Map<TwitchatNotificationEventType, List<TwitchatEventAction>> all = new EnumMap<>(TwitchatNotificationEventType.class);
        for (TwitchatNotificationEventType type : TwitchatNotificationEventType.values()) all.put(type, new ArrayList<>());
        all.get(TwitchatNotificationEventType.STREAM_STARTED).add(action);
        when(twitchatEventActionService.all(user)).thenReturn(all);

        mvc.perform(get("/api/channels/streamer/twitchat/events").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.NO_GAME_DETECTED").isEmpty())
                .andExpect(jsonPath("$.STREAM_STARTED[0].mode").value("RAW"))
                .andExpect(jsonPath("$.STREAM_STARTED[0].enabled").value(false));
    }

    @Test
    void save_replacesTheEventsList() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId);

        mvc.perform(put("/api/channels/streamer/twitchat/events/STREAM_STARTED/actions")
                        .with(userJwt(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        verify(twitchatEventActionService).replace(user, TwitchatNotificationEventType.STREAM_STARTED, PAYLOADS);
    }

    @Test
    void save_invalid_answers400WithEveryError() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId);
        doThrow(new TwitchatValidationException(List.of(new TwitchatValidationError(0, "message", "Required"))))
                .when(twitchatEventActionService).replace(eq(user), any(), any());

        mvc.perform(put("/api/channels/streamer/twitchat/events/STREAM_STARTED/actions")
                        .with(userJwt(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].index").value(0))
                .andExpect(jsonPath("$[0].field").value("message"));
    }

    @Test
    void unknownEvent_is400() throws Exception {
        UUID userId = UUID.randomUUID();
        stubOwner(userId);
        mvc.perform(put("/api/channels/streamer/twitchat/events/NOPE/actions")
                        .with(userJwt(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void test_sendsThePayloadsAsEdited() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = stubOwner(userId);

        mvc.perform(post("/api/channels/streamer/twitchat/events/NO_GAME_DETECTED/test")
                        .with(userJwt(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        verify(twitchatEventDispatcher).sendTest(user, TwitchatNotificationEventType.NO_GAME_DETECTED, PAYLOADS);
    }
}
