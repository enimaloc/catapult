package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.notification.dto.TwitchatAction;
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
                                "https://example.com/act/{{action:DISABLE_BOT}}", null, "primary")))));

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
                                "https://x/{{action:REVERT_CATEGORY}}", null, "secondary")))));

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
    void onStreamStarted_presetActionWithMalformedLowercasePlaceholder_isDropped() {
        // A typo'd placeholder ({{action:disable_bot}} instead of DISABLE_BOT) must still be
        // *detected* as an attempted placeholder so the entry gets dropped, rather than slipping
        // through with the literal unresolved text left in the URL sent to Twitchat.
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "On est en direct !", null, null, null,
                        List.of(new TwitchatRawAction("Stop", "url",
                                "https://example.com/act/{{action:disable_bot}}", null, "primary")))));

        notifier.onStreamStarted(user);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).isEmpty();
        verify(actionTokenService, never()).generate(any(), any(), any());
    }

    @Test
    void onStreamStarted_presetActionWithWhitespaceInPlaceholder_stillResolves() {
        // Stray whitespace around a *valid* placeholder (e.g. from copy/paste) shouldn't break
        // resolution: the detector tolerates it, the type name still parses exactly, and the
        // substitution replaces the original (whitespace-containing) matched text with the token.
        UUID token = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of())))
                .thenReturn(token);
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "On est en direct !", null, null, null,
                        List.of(new TwitchatRawAction("Stop", "url",
                                "https://example.com/act/{{ action:DISABLE_BOT }}", null, "primary")))));

        notifier.onStreamStarted(user);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        assertThat(captor.getValue().actions().get(0).url()).isEqualTo("https://example.com/act/" + token);
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
                                new TwitchatRawAction("Undo", "url", "https://x/{{action:REVERT_CATEGORY}}", null, "secondary"),
                                new TwitchatRawAction("Stop", "url", "https://x/{{action:DISABLE_BOT}}", null, "alert")))));

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
                        List.of(new TwitchatRawAction("Mon site", "url", "https://example.com/static", null, "primary")))));

        notifier.onCategoryChangedByCatapult(user, "222", "New Game", "111");

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        assertThat(captor.getValue().actions().get(0).url()).isEqualTo("https://example.com/static");
        verify(actionTokenService, never()).generate(any(), any(), any());
    }

    @Test
    void onCategoryChangedManually_presetActionWithTwoPlaceholders_resolvesBoth() {
        DetectedGame detected = mock(DetectedGame.class);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setTwitchGameId("111");
        binding.setTwitchGameName("Old Game");
        when(bindingService.findBinding(user, detected)).thenReturn(Optional.of(binding));

        UUID bindToken = UUID.randomUUID();
        UUID revertToken = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.BIND_GAME_CATEGORY), any()))
                .thenReturn(bindToken);
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.REVERT_TO_APP_CATEGORY), any()))
                .thenReturn(revertToken);
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Changed.", null, null, null,
                        List.of(new TwitchatRawAction("Both", "url",
                                "https://x/{{action:BIND_GAME_CATEGORY}}/{{action:REVERT_TO_APP_CATEGORY}}",
                                null, "primary")))));

        // existing binding whose Twitch game id (111) differs from the new one (222) →
        // BIND_GAME_CATEGORY and REVERT_TO_APP_CATEGORY both become pending together.
        notifier.onCategoryChangedManually(user, "222", "New Game");

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        assertThat(captor.getValue().actions().get(0).url())
                .isEqualTo("https://x/" + bindToken + "/" + revertToken);
    }

    @Test
    void onCategoryChangedByCatapult_presetEntryMixesApplicableAndInapplicablePlaceholders_isDroppedWithNoTokenGenerated() {
        // Single entry references both DISABLE_BOT (always pending) and REVERT_CATEGORY
        // (inapplicable here since previousGameId is null). The two-pass discover-then-generate
        // design must find the inapplicable placeholder before generating a token for the
        // applicable one, so the entry is dropped with zero token generation for DISABLE_BOT too.
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Changed.", null, null, null,
                        List.of(new TwitchatRawAction("Mixed", "url",
                                "https://x/{{action:DISABLE_BOT}}/{{action:REVERT_CATEGORY}}", null, "primary")))));

        notifier.onCategoryChangedByCatapult(user, "222", "New Game", null);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).isEmpty();
        verify(actionTokenService, never()).generate(any(), eq(TwitchatActionType.DISABLE_BOT), any());
        verify(actionTokenService, never()).generate(any(), eq(TwitchatActionType.REVERT_CATEGORY), any());
    }

    @Test
    void onStreamStarted_twoPresetActionsReferenceSameType_tokenGeneratedOnceAndSharedAcrossBoth() {
        UUID token = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of())))
                .thenReturn(token);
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "On est en direct !", null, null, null,
                        List.of(
                                new TwitchatRawAction("Stop A", "url", "https://a/{{action:DISABLE_BOT}}", null, "primary"),
                                new TwitchatRawAction("Stop B", "url", "https://b/{{action:DISABLE_BOT}}", null, "alert")))));

        notifier.onStreamStarted(user);

        verify(actionTokenService, times(1)).generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of()));
        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(2);
        assertThat(captor.getValue().actions().get(0).url()).isEqualTo("https://a/" + token);
        assertThat(captor.getValue().actions().get(1).url()).isEqualTo("https://b/" + token);
    }

    @Test
    void onStreamStarted_presetActionTypeNull_defaultsToUrl() {
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "On est en direct !", null, null, null,
                        List.of(new TwitchatRawAction("Mon site", null, "https://example.com/static", null, "primary")))));

        notifier.onStreamStarted(user);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        assertThat(captor.getValue().actions().get(0).actionType()).isEqualTo("url");
    }

    @Test
    void onStreamStarted_presetChatAction_resolvesPlaceholderInMessageField() {
        UUID token = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of())))
                .thenReturn(token);
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Live.", null, null, null,
                        List.of(new TwitchatRawAction("Stop (chat)", "chat", null,
                                "/so {{action:DISABLE_BOT}}", "secondary")))));

        notifier.onStreamStarted(user);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        TwitchatAction action = captor.getValue().actions().get(0);
        assertThat(action.actionType()).isEqualTo("chat");
        assertThat(action.url()).isNull();
        assertThat(action.message()).isEqualTo("/so " + token);
    }

    @Test
    void onCategoryChangedByCatapult_presetActionMessageReferencesInapplicableType_isDropped() {
        // previousGameId == null → REVERT_CATEGORY never becomes pending. The entry's `url` has
        // no placeholder at all, but its `message` does — must still be dropped: applicability is
        // checked across BOTH fields, not just url.
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Changed.", null, null, null,
                        List.of(new TwitchatRawAction("Undo", "chat", null,
                                "/revert {{action:REVERT_CATEGORY}}", "secondary")))));

        notifier.onCategoryChangedByCatapult(user, "222", "New Game", null);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).isEmpty();
        verify(actionTokenService, never()).generate(any(), any(), any());
    }

    @Test
    void onCategoryChangedByCatapult_presetActionReferencesDifferentTypesInUrlAndMessage_bothResolveIndependently() {
        UUID disableBotToken = UUID.randomUUID();
        when(actionTokenService.generate(eq(user.getId()), eq(TwitchatActionType.DISABLE_BOT), eq(Map.of())))
                .thenReturn(disableBotToken);
        when(payloadPresetService.findActivePresetPayload(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT))
                .thenReturn(Optional.of(new TwitchatPresetPayload(
                        "Changed.", null, null, null,
                        List.of(new TwitchatRawAction("Stop", "chat", "https://x/static",
                                "/so {{action:DISABLE_BOT}}", "secondary")))));

        // previousGameId == null: only DISABLE_BOT is pending — url has no placeholder (kept
        // verbatim), message references DISABLE_BOT (resolves). Entry must be kept.
        notifier.onCategoryChangedByCatapult(user, "222", "New Game", null);

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(1);
        assertThat(captor.getValue().actions().get(0).url()).isEqualTo("https://x/static");
        assertThat(captor.getValue().actions().get(0).message()).isEqualTo("/so " + disableBotToken);
    }
}
