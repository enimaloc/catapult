package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameEventListenerLiveCheckTest {

    @Mock private BindingService bindingService;
    @Mock private TwitchService twitchService;
    @Mock private StreamStateService streamStateService;
    @Mock private UserSettingsRepository userSettingsRepository;

    @InjectMocks private GameEventListener listener;

    private UserAccount user;
    private GameBinding binding;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        binding = new GameBinding();
        binding.setStatus(GameBinding.Status.AUTO);
    }

    @Test
    void onGameDetected_whenLive_callsUpdateChannel() {
        when(bindingService.resolveOrCreate(eq(user), any())).thenReturn(binding);
        when(streamStateService.isLive(user)).thenReturn(true);
        DetectedGame game = new DetectedGame("g1", GameBinding.SourceType.STEAM, "Game");

        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(twitchService).updateChannel(user, binding);
        verify(streamStateService, never()).storePending(any(), any());
    }

    @Test
    void onGameDetected_whenNotLive_storesPendingAndSkipsUpdate() {
        when(bindingService.resolveOrCreate(eq(user), any())).thenReturn(binding);
        when(streamStateService.isLive(user)).thenReturn(false);
        DetectedGame game = new DetectedGame("g1", GameBinding.SourceType.STEAM, "Game");

        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(streamStateService).storePending(user, binding);
        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void onStreamOnline_withPendingBinding_appliesItAndClears() {
        when(streamStateService.getPending(user)).thenReturn(Optional.of(binding));

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(twitchService).updateChannel(user, binding);
        verify(streamStateService).clearPending(user);
    }

    @Test
    void onStreamOnline_withoutPendingBinding_doesNothing() {
        when(streamStateService.getPending(user)).thenReturn(Optional.empty());

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void onStreamOffline_callsResetToDefaultAndClearsPending() {
        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        verify(twitchService).resetToDefault(user);
        verify(streamStateService).clearPending(user);
    }

    @Test
    void onNoGameDetected_whenNotLive_skipsRepository() {
        when(streamStateService.isLive(user)).thenReturn(false);

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verify(userSettingsRepository, never()).findById(any());
        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void onNoGameDetected_whenLive_withFallbackConfigured_callsUpdateChannel() {
        when(streamStateService.isLive(user)).thenReturn(true);

        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("fallback-id");
        settings.setNoGameTwitchGameName("Just Chatting");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verify(twitchService).updateChannel(eq(user), any(GameBinding.class));
    }
}
