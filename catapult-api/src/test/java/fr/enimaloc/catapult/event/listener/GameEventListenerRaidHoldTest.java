package fr.enimaloc.catapult.event.listener;

import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
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

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Once the streamer raided out, the "no game" fallback waits for the stream's end or a new game. The real
 * sequence: the game is still detected when the raid starts (the channel already on it, or the streamer
 * having changed it since), then the game is closed and the scheduler reports no game.
 */
@ExtendWith(MockitoExtension.class)
class GameEventListenerRaidHoldTest {

    private static final String NO_GAME_ID = "509658";
    private static final String DOOM_ID = "517";

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
        raidRestore = new RaidRestoreGameService(userSettingsRepository, gate, gameStateService, bindingService, twitchService,
                mock(CustomBindingActivations.class));
        listener = new GameEventListener(bindingService, twitchService, userSettingsRepository, streamStateService,
                channelEventPublisher, raidRestore, mock(CustomBindingActivations.class));
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId(NO_GAME_ID);
        settings.setNoGameTwitchGameName("Just Chatting");
        settings.setApplyDefaultOnNoGame(true);
        settings.setRaidRestoreGameMinutes(15);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(gate.isAvailableReadOnly(user)).thenReturn(true);
        // Doom is still being played when the raid starts
        when(gameStateService.recentlyDetectedGame(eq(user), any(Duration.class))).thenReturn(Optional.of(doom));
        // Only asked by the no-game fallback, which some tests never reach
        lenient().when(streamStateService.isLive(user)).thenReturn(true);

        doomBinding = new GameBinding();
        doomBinding.setId(UUID.randomUUID());
        doomBinding.setUser(user);
        doomBinding.setSourceType(GameBinding.SourceType.STEAM);
        doomBinding.setSourceId("379720");
        doomBinding.setSourceName("DOOM");
        doomBinding.setTwitchGameId(DOOM_ID);
        doomBinding.setStatus(GameBinding.Status.AUTO);
        when(bindingService.findBinding(user, doom)).thenReturn(Optional.of(doomBinding));
    }

    private static GameBinding noGameFallback() {
        return argThat(b -> b != null && NO_GAME_ID.equals(b.getTwitchGameId()));
    }

    private void verifyNoGameFallbackApplied(boolean applied) {
        if (applied) verify(twitchService).updateChannel(eq(user), noGameFallback());
        else verify(twitchService, never()).updateChannel(eq(user), noGameFallback());
    }

    /** The streamer raids out while still playing Doom, the channel already on it, then closes the game. */
    private void raidWhilePlaying_thenCloseTheGame() {
        raidRestore.onRaidedOut(new ChannelRaidedOutEvent(this, user, DOOM_ID));
        verify(twitchService, never()).updateChannel(any(), any());
        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));
    }

    @Test
    void gameClosedAfterTheRaid_noGameFallbackIsSuppressed() {
        raidWhilePlaying_thenCloseTheGame();

        verifyNoGameFallbackApplied(false);
    }

    @Test
    void categoryChangedSinceTheGameStarted_theRaidPutsTheCurrentGameBack_andHoldsItOnceClosed() {
        raidRestore.onRaidedOut(new ChannelRaidedOutEvent(this, user, "12345"));
        verify(twitchService).updateChannel(user, doomBinding);

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verifyNoGameFallbackApplied(false);
    }

    @Test
    void noGameFallback_comesBackOnceTheStreamEnded() {
        raidWhilePlaying_thenCloseTheGame();
        listener.onStreamOffline(new StreamOfflineEvent(this, user));
        clearInvocations(twitchService);

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verifyNoGameFallbackApplied(true);
    }

    @Test
    void noGameFallback_comesBackOnceANewGameIsDetected() {
        raidWhilePlaying_thenCloseTheGame();
        GameBinding hadesBinding = new GameBinding();
        hadesBinding.setId(UUID.randomUUID());
        hadesBinding.setUser(user);
        hadesBinding.setSourceType(GameBinding.SourceType.STEAM);
        hadesBinding.setSourceName("Hades");
        hadesBinding.setTwitchGameId("888");
        hadesBinding.setStatus(GameBinding.Status.AUTO);
        DetectedGame hades = new DetectedGame("1145360", GameBinding.SourceType.STEAM, "Hades");
        when(bindingService.resolveOrCreate(user, hades)).thenReturn(hadesBinding);
        listener.onGameDetected(new GameDetectedEvent(this, user, hades));

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verifyNoGameFallbackApplied(true);
    }
}
