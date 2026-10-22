package fr.enimaloc.catapult.event.listener;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Once a raid put the last game back, the "no game" fallback waits for the stream's end or a new game. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GameEventListenerRaidHoldTest {

    private static final String NO_GAME_ID = "509658";

    @Mock private BindingService bindingService;
    @Mock private TwitchService twitchService;
    @Mock private StreamStateService streamStateService;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private ChannelEventPublisher channelEventPublisher;
    @Mock private RaidRestoreGateService gate;
    @Mock private GameStateService gameStateService;

    private RaidRestoreGameService raidRestore;
    private GameEventListener listener;
    private UserAccount user;
    private GameBinding doomBinding;
    private final DetectedGame doom = new DetectedGame("379720", GameBinding.SourceType.STEAM, "DOOM");

    @BeforeEach
    void setUp() {
        raidRestore = new RaidRestoreGameService(userSettingsRepository, gate, gameStateService, bindingService, twitchService);
        listener = new GameEventListener(bindingService, twitchService, userSettingsRepository, streamStateService,
                channelEventPublisher, raidRestore);
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId(NO_GAME_ID);
        settings.setNoGameTwitchGameName("Just Chatting");
        settings.setApplyDefaultOnNoGame(true);
        settings.setRaidRestoreGameMinutes(15);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(streamStateService.isLive(user)).thenReturn(true);
        when(gate.isAvailableReadOnly(user)).thenReturn(true);
        when(gameStateService.recentlyDetectedGame(eq(user), any(Duration.class))).thenReturn(Optional.of(doom));

        doomBinding = new GameBinding();
        doomBinding.setId(UUID.randomUUID());
        doomBinding.setUser(user);
        doomBinding.setSourceType(GameBinding.SourceType.STEAM);
        doomBinding.setSourceId("379720");
        doomBinding.setSourceName("DOOM");
        doomBinding.setTwitchGameId("517");
        doomBinding.setStatus(GameBinding.Status.AUTO);
        when(bindingService.findBinding(user, doom)).thenReturn(Optional.of(doomBinding));

        raidRestore.onRaidedOut(new ChannelRaidedOutEvent(this, user, NO_GAME_ID));
        verify(twitchService).updateChannel(user, doomBinding);
        clearInvocations(twitchService);
    }

    private static GameBinding noGameFallback() {
        return argThat(b -> b != null && NO_GAME_ID.equals(b.getTwitchGameId()));
    }

    private void verifyNoGameFallbackApplied(boolean applied) {
        if (applied) verify(twitchService).updateChannel(eq(user), noGameFallback());
        else verify(twitchService, never()).updateChannel(eq(user), noGameFallback());
    }

    @Test
    void noGameFallback_isSuppressedAfterTheRaid() {
        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verifyNoGameFallbackApplied(false);
    }

    @Test
    void noGameFallback_comesBackOnceTheStreamEnded() {
        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verifyNoGameFallbackApplied(true);
    }

    @Test
    void noGameFallback_comesBackOnceANewGameIsDetected() {
        GameBinding other = new GameBinding();
        other.setId(UUID.randomUUID());
        other.setUser(user);
        other.setSourceType(GameBinding.SourceType.STEAM);
        other.setSourceName("Hades");
        other.setTwitchGameId("888");
        other.setStatus(GameBinding.Status.AUTO);
        DetectedGame hades = new DetectedGame("1145360", GameBinding.SourceType.STEAM, "Hades");
        when(bindingService.resolveOrCreate(user, hades)).thenReturn(other);
        listener.onGameDetected(new GameDetectedEvent(this, user, hades));

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verifyNoGameFallbackApplied(true);
    }
}
