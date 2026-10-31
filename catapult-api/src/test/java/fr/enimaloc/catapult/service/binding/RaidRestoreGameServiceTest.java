package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.category.CategoryApplier;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RaidRestoreGameServiceTest {

    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private RaidRestoreGateService gate;
    @Mock private GameStateService gameStateService;
    @Mock private GameBindingLookup bindingLookup;
    @Mock private TwitchService twitchService;
    @Mock private StreamStateService streamStateService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private CustomBindingActivations customBindingActivations;

    private final RaidRestoreHold hold = new RaidRestoreHold();
    private RaidRestoreGameService service;
    private UserAccount user;
    private UserSettings settings;
    private GameBinding binding;
    private final DetectedGame doom = new DetectedGame("379720", GameBinding.SourceType.STEAM, "DOOM");

    @BeforeEach
    void setUp() {
        CategoryApplier applier = new CategoryApplier(twitchService, streamStateService);
        CategoryChain chain = new CategoryChain(customBindingActivations, streamStateService, userSettingsRepository,
                hold, gameStateService, userAccountRepository, applier, gate, bindingLookup, mock(ExtensionLayoutApplier.class));
        service = new RaidRestoreGameService(chain, applier, hold);
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        settings = new UserSettings();
        settings.setRaidRestoreGameMinutes(15);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        // Not reached by the tests that stop at an earlier check
        lenient().when(gate.isAvailableReadOnly(user)).thenReturn(true);
        lenient().when(gameStateService.recentlyDetectedGame(user, Duration.ofMinutes(15))).thenReturn(Optional.of(doom));

        binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId("379720");
        binding.setSourceName("DOOM");
        binding.setTwitchGameId("517");
        binding.setTwitchGameName("DOOM");
        binding.setStatus(GameBinding.Status.AUTO);
        lenient().when(bindingLookup.findByDetectedGame(user, doom)).thenReturn(Optional.of(binding));
        lenient().when(twitchService.updateChannelReporting(user, binding)).thenReturn(ChannelUpdateOutcome.APPLIED);
    }

    private void raid(String currentCategoryId) {
        service.onRaidedOut(new ChannelRaidedOutEvent(this, user, currentCategoryId));
    }

    @Test
    void restoresTheGameDetectedWithinTheDelay_asACatapultChange() {
        raid("509658");

        // updateChannel records the Catapult self-change marker (TwitchServiceImpl), so the
        // resulting channel.update is reported as Catapult's, never as a manual change.
        verify(twitchService).updateChannelReporting(user, binding);
        assertThat(hold.isHolding(user)).isTrue();
    }

    @Test
    void doesNothingWhileACustomBindingIsActive() {
        when(customBindingActivations.active(user))
                .thenReturn(Optional.of(new fr.enimaloc.catapult.domain.binding.CustomBindingActivation()));

        raid("509658");

        verify(twitchService, never()).updateChannelReporting(any(), any());
        assertThat(hold.isHolding(user)).isFalse();
    }

    @Test
    void restoresWhenTheCurrentCategoryIsUnknown() {
        raid(null);

        verify(twitchService).updateChannelReporting(user, binding);
    }

    @Test
    void doesNothingWhenNoGameWasDetectedWithinTheDelay() {
        when(gameStateService.recentlyDetectedGame(user, Duration.ofMinutes(15))).thenReturn(Optional.empty());

        raid("509658");

        verify(twitchService, never()).updateChannelReporting(any(), any());
        assertThat(hold.isHolding(user)).isFalse();
    }

    @Test
    void doesNothingWhenTheSettingIsOff() {
        settings.setRaidRestoreGameMinutes(null);

        raid("509658");

        verify(twitchService, never()).updateChannelReporting(any(), any());
        verify(gate, never()).isAvailableReadOnly(any());
    }

    @Test
    void doesNothingWithoutSettings() {
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.empty());

        raid("509658");

        verify(twitchService, never()).updateChannelReporting(any(), any());
    }

    @Test
    void doesNothingWhenTheGateIsOff() {
        when(gate.isAvailableReadOnly(user)).thenReturn(false);

        raid("509658");

        verify(twitchService, never()).updateChannelReporting(any(), any());
        assertThat(hold.isHolding(user)).isFalse();
    }

    @Test
    void alreadyOnThatGame_changesNothingButStillHoldsTheNoGameFallback() {
        raid("517");

        verify(twitchService, never()).updateChannelReporting(any(), any());
        assertThat(hold.isHolding(user)).isTrue();
    }

    @Test
    void theHoldIsTakenBeforeTheCategoryIsChanged() {
        boolean[] holdingDuringTheChange = new boolean[1];
        doAnswer(invocation -> holdingDuringTheChange[0] = hold.isHolding(user))
                .when(twitchService).updateChannelReporting(user, binding);

        raid("509658");

        assertThat(holdingDuringTheChange[0]).isTrue();
    }

    @Test
    void aFailedRestoreReleasesTheHold() {
        doThrow(new IllegalStateException("Twitch down")).when(twitchService).updateChannelReporting(user, binding);

        raid("509658");

        assertThat(hold.isHolding(user)).isFalse();
    }

    @Test
    void aRestoreTwitchRefusedReleasesTheHold() {
        when(twitchService.updateChannelReporting(user, binding)).thenReturn(ChannelUpdateOutcome.FAILED);

        raid("509658");

        assertThat(hold.isHolding(user)).isFalse();
    }

    @Test
    void aRestoreTwitchSkippedReleasesTheHold() {
        when(twitchService.updateChannelReporting(user, binding)).thenReturn(ChannelUpdateOutcome.SKIPPED);

        raid("509658");

        assertThat(hold.isHolding(user)).isFalse();
    }

    @Test
    void doesNothingForAnIgnoredOrUnresolvedGame() {
        binding.setIgnored(true);
        raid("509658");
        binding.setIgnored(false);
        binding.setTwitchGameId(null);
        raid("509658");
        when(bindingLookup.findByDetectedGame(user, doom)).thenReturn(Optional.empty());
        raid("509658");

        verify(twitchService, never()).updateChannelReporting(any(), any());
    }

    @Test
    void releaseHold_endsTheHold() {
        raid("509658");

        hold.release(user);

        assertThat(hold.isHolding(user)).isFalse();
    }
}
