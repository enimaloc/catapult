package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.notification.dto.TwitchatNotification;
import fr.enimaloc.catapult.service.notification.dto.TwitchatPresetPayload;
import fr.enimaloc.catapult.service.notification.dto.TwitchatRawAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwitchatNotifierTest {

    @Mock private TwitchatWidgetSettingsService widgetSettingsService;
    @Mock private TwitchatActionTokenService actionTokenService;
    @Mock private TwitchatPayloadPresetService payloadPresetService;
    @Mock private ChannelEventPublisher channelEventPublisher;
    @Mock private GameStateService gameStateService;
    @Mock private BindingService bindingService;
    @InjectMocks private TwitchatNotifier notifier;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        ReflectionTestUtils.setField(notifier, "publicWebUrl", "https://catapult.test");

        TwitchatWidgetSettings enabledSettings = new TwitchatWidgetSettings();
        enabledSettings.setEnabled(true);
        lenient().when(widgetSettingsService.getOrCreate(user)).thenReturn(enabledSettings);
        lenient().when(actionTokenService.generate(any(), any(), any())).thenReturn(UUID.randomUUID());
        lenient().when(payloadPresetService.findActivePresetPayload(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void onCategoryChangedByCatapult_disabledWidget_doesNotPublish() {
        TwitchatWidgetSettings disabled = new TwitchatWidgetSettings();
        disabled.setEnabled(false);
        when(widgetSettingsService.getOrCreate(user)).thenReturn(disabled);

        notifier.onCategoryChangedByCatapult(user, "222", "New Game", "111");

        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void onCategoryChangedByCatapult_generatesRevertAndDisableBotTokens() {
        notifier.onCategoryChangedByCatapult(user, "222", "New Game", "111");

        verify(actionTokenService).generate(eq(user.getId()), eq(TwitchatActionType.REVERT_CATEGORY),
                eq(Map.of("gameId", "111", "gameName", "")));
        verify(actionTokenService).generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of()));

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(2);
        assertThat(captor.getValue().icon()).isEqualTo("change");
        assertThat(captor.getValue().authorName()).isEqualTo("Catapult");
    }

    @Test
    void onCategoryChangedManually_gameDetected_addsBindButton() {
        DetectedGame detected = mock(DetectedGame.class);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setTwitchGameId("111");
        binding.setTwitchGameName("Old Game");
        when(bindingService.findBinding(user, detected)).thenReturn(Optional.of(binding));

        notifier.onCategoryChangedManually(user, "222", "New Game");

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(2); // bind + revert-to-app (differ: 111 != 222)
        assertThat(captor.getValue().icon()).isEqualTo("user");
    }

    @Test
    void onCategoryChangedManually_gameDetectedButNoBindingYet_skipsBindButtonAndCreatesNothing() {
        DetectedGame detected = mock(DetectedGame.class);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(bindingService.findBinding(user, detected)).thenReturn(Optional.empty());

        notifier.onCategoryChangedManually(user, "222", "New Game");

        verify(bindingService, never()).resolveOrCreate(any(), any());
        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).isEmpty();
    }

    @Test
    void onCategoryChangedManually_noGameDetected_onlyRevertButtonIfDifferent() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        notifier.onCategoryChangedManually(user, "222", "New Game");

        verify(bindingService, never()).resolveOrCreate(any(), any());
        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).isEmpty();
    }

    @Test
    void onCategoryChangedByCatapult_noPreviousCategory_omitsRevertButton() {
        notifier.onCategoryChangedByCatapult(user, "222", "New Game", null);

        verify(actionTokenService, never()).generate(any(), eq(TwitchatActionType.REVERT_CATEGORY), any());
        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1); // disable-bot only
    }

    @Test
    void publishFailure_isSwallowedSoCallersAreNeverBroken() {
        doThrow(new RuntimeException("redis down"))
                .when(channelEventPublisher).twitchatNotify(any(), any());

        assertThatCode(() -> notifier.onStreamStarted(user)).doesNotThrowAnyException();
    }

    @Test
    void settingsLookupFailure_isSwallowed() {
        when(widgetSettingsService.getOrCreate(user)).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> notifier.onBotToggled(user, true)).doesNotThrowAnyException();
        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void onBotToggled_enabled_generatesDisableBotButton() {
        notifier.onBotToggled(user, true);

        verify(actionTokenService).generate(user.getId(), TwitchatActionType.DISABLE_BOT, Map.of());
        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().icon()).isEqualTo("online");
    }

    @Test
    void onBotToggled_disabled_generatesEnableBotButton() {
        notifier.onBotToggled(user, false);

        verify(actionTokenService).generate(user.getId(), TwitchatActionType.ENABLE_BOT, Map.of());
        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().icon()).isEqualTo("offline");
    }

    @Test
    void onStreamStarted_generatesDisableBotButton() {
        notifier.onStreamStarted(user);

        verify(actionTokenService).generate(user.getId(), TwitchatActionType.DISABLE_BOT, Map.of());
        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().icon()).isEqualTo("live");
    }

    @Test
    void onStreamStarted_presetRawAction_resolvesPlaceholderToRawToken() {
        UUID token = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of())))
                .thenReturn(token);
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "On est en direct !", null, "custom-icon", "MonBot",
                        List.of(new TwitchatRawAction("Stop", "url",
                                "https://example.com/act/{{action:DISABLE_BOT}}", "primary")))));

        notifier.onStreamStarted(user);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        TwitchatNotification n = captor.getValue();
        assertThat(n.message()).isEqualTo("On est en direct !");
        assertThat(n.icon()).isEqualTo("custom-icon");
        assertThat(n.style()).isEqualTo("message"); // preset omitted style → default
        assertThat(n.authorName()).isEqualTo("MonBot");
        assertThat(n.actions()).hasSize(1);
        assertThat(n.actions().get(0).label()).isEqualTo("Stop");
        assertThat(n.actions().get(0).theme()).isEqualTo("primary");
        assertThat(n.actions().get(0).url()).isEqualTo("https://example.com/act/" + token);
    }

    @Test
    void onCategoryChangedByCatapult_presetSubstitutesGameNameVariable() {
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        ">> {{gameName}} <<", null, null, null, null)));

        notifier.onCategoryChangedByCatapult(user, "222", "Elden Ring", null);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().message()).isEqualTo(">> Elden Ring <<");
    }

    @Test
    void onCategoryChangedByCatapult_presetActionReferencingInapplicableType_isDropped() {
        // Preset's only action entry references REVERT_CATEGORY, but previousGameId is null so
        // that type never becomes pending — must not crash, and the entry must not appear.
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Changed.", null, null, null,
                        List.of(new TwitchatRawAction("Undo", "url",
                                "https://x/{{action:REVERT_CATEGORY}}", "secondary")))));

        notifier.onCategoryChangedByCatapult(user, "222", "New Game", null);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        // The preset owns the array completely: it never mentioned DISABLE_BOT (unlike the old
        // override-map model, which always rendered every applicable pending action), and its
        // one REVERT_CATEGORY entry was dropped as inapplicable — so the result is empty.
        assertThat(captor.getValue().actions()).isEmpty();
        verify(actionTokenService, never()).generate(any(), any(), any());
    }

    @Test
    void onCategoryChangedByCatapult_presetActionsMixApplicableAndInapplicable_keepsOnlyApplicable() {
        UUID disableBotToken = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of())))
                .thenReturn(disableBotToken);
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Changed.", null, null, null,
                        List.of(
                                new TwitchatRawAction("Undo", "url", "https://x/{{action:REVERT_CATEGORY}}", "secondary"),
                                new TwitchatRawAction("Stop", "url", "https://x/{{action:DISABLE_BOT}}", "alert")))));

        // previousGameId == null → REVERT_CATEGORY never becomes pending, DISABLE_BOT always does
        notifier.onCategoryChangedByCatapult(user, "222", "New Game", null);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        assertThat(captor.getValue().actions().get(0).label()).isEqualTo("Stop");
        assertThat(captor.getValue().actions().get(0).url()).isEqualTo("https://x/" + disableBotToken);
        verify(actionTokenService, never()).generate(any(), eq(TwitchatActionType.REVERT_CATEGORY), any());
    }

    @Test
    void onCategoryChangedByCatapult_presetActionWithoutPlaceholder_keptVerbatim() {
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Changed.", null, null, null,
                        List.of(new TwitchatRawAction("Mon site", "url", "https://example.com/static", "primary")))));

        notifier.onCategoryChangedByCatapult(user, "222", "New Game", "111");

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        assertThat(captor.getValue().actions().get(0).url()).isEqualTo("https://example.com/static");
        verify(actionTokenService, never()).generate(any(), any(), any());
    }
}
