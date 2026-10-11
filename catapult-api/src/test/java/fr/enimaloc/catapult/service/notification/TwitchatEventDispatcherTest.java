package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatDispatch;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.BOT_DISABLED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.NO_GAME_DETECTED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.STREAM_STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwitchatEventDispatcherTest {

    @Mock TwitchatWidgetSettingsService widgetSettingsService;
    @Mock TwitchatActionTokenService actionTokenService;
    @Mock TwitchatEventActionService eventActionService;
    @Mock ChannelEventPublisher channelEventPublisher;
    @Mock GameStateService gameStateService;
    @Mock BindingService bindingService;

    TwitchatEventDispatcher dispatcher;
    UserAccount user;
    TwitchatWidgetSettings settings;

    @BeforeEach
    void setUp() {
        dispatcher = new TwitchatEventDispatcher(widgetSettingsService, actionTokenService, eventActionService,
                channelEventPublisher, gameStateService, bindingService);
        ReflectionTestUtils.setField(dispatcher, "publicWebUrl", "https://catapult.test/");
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername("streamer");
        settings = new TwitchatWidgetSettings();
        settings.setEnabled(true);
        lenient().when(widgetSettingsService.getOrCreate(user)).thenReturn(settings);
        lenient().when(eventActionService.parseData(any())).thenAnswer(invocation ->
                new fr.enimaloc.catapult.common.twitchat.TwitchatPayloadValidator(
                        fr.enimaloc.catapult.common.twitchat.TwitchatCatalog.load()).parse(invocation.getArgument(0)));
    }

    private TwitchatEventAction row(String action, String json) {
        TwitchatEventAction row = new TwitchatEventAction();
        row.setAction(action);
        row.setDataJson(json);
        row.setEnabled(true);
        return row;
    }

    private TwitchatDispatch published() {
        ArgumentCaptor<TwitchatDispatch> dispatch = ArgumentCaptor.forClass(TwitchatDispatch.class);
        verify(channelEventPublisher).twitchatDispatch(eq(user.getId()), dispatch.capture());
        return dispatch.getValue();
    }

    @Test
    void categoryChangedByCatapult_rendersEveryEnabledPayload_inOrder() {
        when(eventActionService.enabledActions(user, CATEGORY_CHANGED_BY_CATAPULT)).thenReturn(List.of(
                row("CUSTOM_CHAT_MESSAGE", """
                        {"message":"{{previousGameName}} -> {{gameName}}","actions":[
                          {"label":"Revert","url":"{{baseUrl}}/widget/twitchat/action/{{action:REVERT_CATEGORY}}"}]}"""),
                row("EXECUTE_TRIGGER", "{\"id\":\"t-1\"}")));
        UUID token = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.REVERT_CATEGORY), any())).thenReturn(token);

        dispatcher.onCategoryChangedByCatapult(user, "g2", "Celeste", "g1", "Hades");

        TwitchatDispatch dispatch = published();
        assertThat(dispatch.channel()).isEqualTo("streamer");
        assertThat(dispatch.eventType()).isEqualTo("CATEGORY_CHANGED_BY_CATAPULT");
        assertThat(dispatch.payloads()).extracting(p -> p.action()).containsExactly("CUSTOM_CHAT_MESSAGE", "EXECUTE_TRIGGER");
        Map<String, Object> chat = dispatch.payloads().getFirst().data();
        assertThat(chat.get("message")).isEqualTo("Hades -> Celeste");
        assertThat(chat.get("actions")).isEqualTo(List.of(Map.of("label", "Revert",
                "url", "https://catapult.test/widget/twitchat/action/" + token)));
    }

    @Test
    void noPreviousCategory_dropsTheRevertButton_withoutMintingIt() {
        when(eventActionService.enabledActions(user, CATEGORY_CHANGED_BY_CATAPULT)).thenReturn(List.of(
                row("CUSTOM_CHAT_MESSAGE", "{\"message\":\"x\",\"actions\":[{\"label\":\"Revert\",\"url\":\"{{action:REVERT_CATEGORY}}\"}]}")));

        dispatcher.onCategoryChangedByCatapult(user, "g2", "Celeste", null, null);

        assertThat(published().payloads().getFirst().data().get("actions")).isEqualTo(List.of());
        verify(actionTokenService, never()).generate(any(), eq(TwitchatActionType.REVERT_CATEGORY), any());
    }

    @Test
    void noPayloads_publishesNothing() {
        when(eventActionService.enabledActions(user, STREAM_STARTED)).thenReturn(List.of());
        dispatcher.onStreamStarted(user);
        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void everyPayloadSkipped_publishesNothing() {
        when(eventActionService.enabledActions(user, BOT_DISABLED)).thenReturn(List.of(
                row("SEND_MESSAGE", "{\"message\":\"{{action:DISABLE_BOT}}\"}")));
        dispatcher.onBotToggled(user, false);
        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void widgetDisabled_dispatchesNothing() {
        settings.setEnabled(false);
        dispatcher.onStreamStarted(user);
        verifyNoInteractions(eventActionService, channelEventPublisher);
    }

    @Test
    void noGameDetected_dispatchesItsPayloads() {
        when(eventActionService.enabledActions(user, NO_GAME_DETECTED)).thenReturn(List.of(row("SEND_MESSAGE", "{\"message\":\"Pause\"}")));
        dispatcher.onNoGameDetected(new NoGameDetectedEvent(this, user));
        assertThat(published().eventType()).isEqualTo("NO_GAME_DETECTED");
    }

    @Test
    void aFailure_isNeverPropagated() {
        when(eventActionService.enabledActions(user, STREAM_STARTED)).thenThrow(new IllegalStateException("db down"));
        dispatcher.onStreamStarted(user);
        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void sendTest_usesEphemeralTokensAndFakeVariables_includingDisabledOnes() {
        dispatcher.sendTest(user, CATEGORY_CHANGED_BY_CATAPULT, List.of(
                new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{\"message\":\"{{gameName}} {{action:DISABLE_BOT}}\"}", false)));

        String message = (String) published().payloads().getFirst().data().get("message");
        assertThat(message).startsWith("Jeu de test ").doesNotContain("{{");
        verifyNoInteractions(actionTokenService);
    }

    @Test
    void sendTest_widgetDisabled_isAConflict() {
        settings.setEnabled(false);
        assertThatThrownBy(() -> dispatcher.sendTest(user, STREAM_STARTED, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void sendTest_validatesFirst() {
        org.mockito.Mockito.doThrow(new TwitchatValidationException(List.of())).when(eventActionService).validate(any());
        assertThatThrownBy(() -> dispatcher.sendTest(user, STREAM_STARTED, List.of()))
                .isInstanceOf(TwitchatValidationException.class);
    }
}
