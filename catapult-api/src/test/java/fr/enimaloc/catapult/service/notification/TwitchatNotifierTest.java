package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.notification.dto.TwitchatNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwitchatNotifierTest {

    @Mock private TwitchatWidgetSettingsService widgetSettingsService;
    @Mock private TwitchatActionTokenService actionTokenService;
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
    }

    @Test
    void onCategoryChangedManually_gameDetected_addsBindButton() {
        DetectedGame detected = mock(DetectedGame.class);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setTwitchGameId("111");
        binding.setTwitchGameName("Old Game");
        when(bindingService.resolveOrCreate(user, detected)).thenReturn(binding);

        notifier.onCategoryChangedManually(user, "222", "New Game");

        ArgumentCaptor<TwitchatNotification> captor = ArgumentCaptor.forClass(TwitchatNotification.class);
        verify(channelEventPublisher).twitchatNotify(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().actions()).hasSize(2); // bind + revert-to-app (differ: 111 != 222)
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
    void onBotToggled_enabled_generatesDisableBotButton() {
        notifier.onBotToggled(user, true);

        verify(actionTokenService).generate(user.getId(), TwitchatActionType.DISABLE_BOT, Map.of());
    }

    @Test
    void onBotToggled_disabled_generatesEnableBotButton() {
        notifier.onBotToggled(user, false);

        verify(actionTokenService).generate(user.getId(), TwitchatActionType.ENABLE_BOT, Map.of());
    }

    @Test
    void onStreamStarted_generatesDisableBotButton() {
        notifier.onStreamStarted(user);

        verify(actionTokenService).generate(user.getId(), TwitchatActionType.DISABLE_BOT, Map.of());
    }
}
