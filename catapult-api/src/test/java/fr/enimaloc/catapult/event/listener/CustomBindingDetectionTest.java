package fr.enimaloc.catapult.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The detection path and the stream events with a custom binding active: the real {@link GameEventListener} and
 * {@link CustomBindingActivations} (so the experiment gate is the real one), everything else mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomBindingDetectionTest {

    @Mock private BindingService bindingService;
    @Mock private TwitchService twitchService;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private StreamStateService streamStateService;
    @Mock private ChannelEventPublisher channelEventPublisher;
    @Mock private CustomBindingActivationRepository activationRepository;
    @Mock private ExperimentService experimentService;
    @Mock private GameStateService gameStateService;

    private CustomBindingActivations activations;
    private GameEventListener listener;

    private UserAccount user;
    private UserSettings settings;
    private CustomBinding art;
    private DetectedGame game;
    private GameBinding gameBinding;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        settings = new UserSettings();
        settings.setNoGameTwitchGameId("509658");
        settings.setNoGameTwitchGameName("Just Chatting");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        art = new CustomBinding();
        art.setId(UUID.randomUUID());
        art.setUser(user);
        art.setName("Art");
        art.setTwitchGameId("509660");
        art.setTwitchGameName("Art");

        game = new DetectedGame("g1", GameBinding.SourceType.STEAM, "Game");
        gameBinding = new GameBinding();
        gameBinding.setId(UUID.randomUUID());
        gameBinding.setUser(user);
        gameBinding.setStatus(GameBinding.Status.AUTO);
        gameBinding.setSourceType(GameBinding.SourceType.STEAM);
        gameBinding.setSourceId("g1");
        gameBinding.setSourceName("Game");
        when(bindingService.resolveOrCreate(user, game)).thenReturn(gameBinding);

        when(experimentService.evaluateGate(user, CustomBindingService.EXPERIMENT_KEY)).thenReturn(true);
        activations = new CustomBindingActivations(activationRepository, experimentService, channelEventPublisher);
        listener = new GameEventListener(bindingService, twitchService, userSettingsRepository,
                streamStateService, channelEventPublisher, mock(RaidRestoreGameService.class), activations);
    }

    private void activate(boolean uninterruptible) {
        art.setUninterruptible(uninterruptible);
        when(activationRepository.findById(user.getId()))
                .thenReturn(Optional.of(new CustomBindingActivation(user.getId(), art, Instant.now())));
        when(activationRepository.existsById(user.getId())).thenReturn(true);
    }

    private void live(boolean live) {
        when(streamStateService.isLive(user)).thenReturn(live);
    }

    private static GameBinding isArt() {
        return argThat(applied -> applied != null
                && "509660".equals(applied.getTwitchGameId())
                && (CustomBinding.SOURCE_NAME_PREFIX + "Art").equals(applied.getSourceName()));
    }

    @Test
    void anAdminMappingChange_isNotAppliedWhileACustomBindingIsActive() {
        activate(false);

        assertThat(listener.applyBindingIfLive(user, gameBinding)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

        verify(twitchService, never()).updateChannelReporting(any(), any());
        verify(activationRepository, never()).deleteById(any());
    }

    @Test
    void interruptible_isInterruptedByAnyDetection_andGameApplies() {
        activate(false);
        live(true);

        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(activationRepository).deleteById(user.getId());
        verify(channelEventPublisher).customBindingChanged(user.getId(), null);
        verify(twitchService).updateChannel(user, gameBinding);
    }

    @Test
    void sameGameRelaunched_interrupts() {
        activate(false);
        live(true);

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verify(activationRepository, never()).deleteById(any());
        verify(twitchService, never()).updateChannel(any(), any());

        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(activationRepository).deleteById(user.getId());
        verify(twitchService).updateChannel(user, gameBinding);
    }

    @Test
    void uninterruptible_ignoresDetection_thenGameTakesOverOnDeactivate() {
        activate(true);
        live(true);

        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(activationRepository, never()).deleteById(any());
        verify(twitchService, never()).updateChannel(any(), any());
        verify(streamStateService, never()).storePending(any(), any());

        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(game));
        CustomBindingService service = new CustomBindingService(mock(CustomBindingRepository.class), activations,
                twitchService, experimentService, gameStateService, streamStateService, userSettingsRepository,
                mock(UserAccountRepository.class), mock(OAuthTokenRepository.class), listener, channelEventPublisher);

        service.deactivate(user);

        verify(activationRepository).deleteById(user.getId());
        verify(twitchService).updateChannel(user, gameBinding);
    }

    @Test
    void noGameDetected_neverInterrupts_andNoGameFallbackSkipped() {
        activate(false);
        live(true);
        settings.setApplyDefaultOnNoGame(true);

        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verify(activationRepository, never()).deleteById(any());
        verify(channelEventPublisher, never()).customBindingChanged(any(), any());
        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void streamStart_reappliesActive_overDefault() {
        activate(false);
        settings.setApplyDefaultOnStreamStart(true);
        GameBinding pending = new GameBinding();
        when(streamStateService.getPending(user)).thenReturn(Optional.of(pending));

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(twitchService).updateChannel(eq(user), isArt());
        verify(twitchService, never()).resetToDefault(any());
        verify(twitchService, never()).updateChannel(user, pending);
        verify(streamStateService, never()).clearPending(any());
        verify(activationRepository, never()).deleteById(any());
    }

    @Test
    void streamEnd_clearsActivation_thenStreamEndChoiceApplies() {
        activate(true);
        settings.setApplyDefaultOnStreamEnd(true);

        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        InOrder order = inOrder(activationRepository, twitchService);
        order.verify(activationRepository).deleteById(user.getId());
        order.verify(twitchService).resetToDefault(user);
        verify(channelEventPublisher).customBindingChanged(user.getId(), null);
        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void editingAGameBinding_duringActivation_isSavedNotApplied() {
        activate(false);
        live(true);
        GameBindingRepository gameBindings = mock(GameBindingRepository.class);
        when(gameBindings.findByIdAndUser(gameBinding.getId(), user)).thenReturn(Optional.of(gameBinding));
        // The edited binding is the game being played: without an activation it would go to Twitch at once
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(game));
        BindingService realBindings = new BindingService(gameBindings, mock(IgdbService.class),
                mock(IgdbGameDetailsService.class), twitchService, mock(TwResolverService.class), gameStateService,
                mock(GameMappingRepository.class), activations);

        realBindings.setTwitchGame(user, gameBinding.getId(), "1234", "Other");

        verify(gameBindings).save(argThat(saved -> "1234".equals(saved.getTwitchGameId())));
        verify(twitchService, never()).updateChannel(any(), any());
        verify(activationRepository, never()).deleteById(any());
    }

    @Test
    void gateClosed_activationIsIgnored() {
        activate(true);
        live(true);
        when(experimentService.evaluateGate(user, CustomBindingService.EXPERIMENT_KEY)).thenReturn(false);
        settings.setApplyDefaultOnStreamStart(true);

        listener.onGameDetected(new GameDetectedEvent(this, user, game));
        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(twitchService).updateChannel(user, gameBinding);
        verify(twitchService).resetToDefault(user);
        verify(twitchService, never()).updateChannel(eq(user), isArt());
        verify(activationRepository, never()).deleteById(any());
    }
}
