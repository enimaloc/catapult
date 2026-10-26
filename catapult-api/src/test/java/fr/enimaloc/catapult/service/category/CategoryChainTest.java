package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryChain.DetectionGuard;
import fr.enimaloc.catapult.service.category.CategoryDecision.Action;
import fr.enimaloc.catapult.service.category.CategoryOutcome.Reason;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameDetected;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameAfterCustomBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link CategoryChain#decide} for the detection family, one test per rule outcome, and its detection guard. */
@ExtendWith(MockitoExtension.class)
class CategoryChainTest {

    @Mock private CustomBindingActivations activations;
    @Mock private StreamStateService streamStateService;
    @Mock private UserSettingsRepository settingsRepository;
    @Mock private CategoryApplier applier;

    private final RaidRestoreHold hold = new RaidRestoreHold();
    private CategoryChain chain;
    private UserAccount user;
    private UserSettings settings;
    private GameBinding binding;

    @BeforeEach
    void setUp() {
        chain = new CategoryChain(activations, streamStateService, settingsRepository, hold, applier);
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        settings = new UserSettings();
        settings.setNoGameTwitchGameId("509658");
        settings.setNoGameTwitchGameName("Just Chatting");
        settings.setIncompleteFallbackTwitchGameId("1469308723");
        settings.setIncompleteFallbackTwitchGameName("Software and Game Development");
        settings.getIncompleteFallbackCcls().add("ProfanityVulgarity");

        binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId("570");
        binding.setSourceName("Dota 2");
        binding.setTwitchGameId("29595");
        binding.setTwitchGameName("Dota 2");
        binding.setStatus(GameBinding.Status.AUTO);
    }

    private void live(boolean live) {
        when(streamStateService.isLive(user)).thenReturn(live);
    }

    private void withSettings() {
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
    }

    private void activeCustom(boolean uninterruptible) {
        CustomBinding custom = new CustomBinding();
        custom.setId(UUID.randomUUID());
        custom.setUser(user);
        custom.setName("Art");
        custom.setUninterruptible(uninterruptible);
        when(activations.active(user))
                .thenReturn(Optional.of(new CustomBindingActivation(user.getId(), custom, Instant.EPOCH)));
    }

    // ---- GameDetected ----

    @Test
    void gameDetected_live_updatesTheBinding() {
        live(true);

        assertThat(chain.decide(user, new GameDetected(binding)))
                .isEqualTo(CategoryDecision.update(CategoryRule.GAME_BINDING, binding));
    }

    @Test
    void gameDetected_offline_storesItPending() {
        live(false);

        assertThat(chain.decide(user, new GameDetected(binding)))
                .isEqualTo(CategoryDecision.storePending(CategoryRule.GAME_BINDING, binding));
    }

    @Test
    void gameDetected_ignored_isTheNoGameFallback() {
        binding.setIgnored(true);
        live(true);
        withSettings();

        CategoryDecision decision = chain.decide(user, new GameDetected(binding));

        assertThat(decision.action()).isEqualTo(Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.NO_GAME_FALLBACK);
        assertThat(decision.reason()).isEqualTo(Reason.NONE);
        assertThat(decision.twitchGameId()).isNull();
        assertThat(decision.twitchGameName()).isNull();
        GameBinding sent = decision.binding();
        assertThat(sent.getId()).isNull();
        assertThat(sent.getUser()).isSameAs(user);
        assertThat(sent.getSourceType()).isEqualTo(GameBinding.SourceType.MANUAL);
        assertThat(sent.getSourceName()).isEqualTo("no-game-fallback");
        assertThat(sent.getTwitchGameId()).isEqualTo("509658");
        assertThat(sent.getTwitchGameName()).isEqualTo("Just Chatting");
        assertThat(sent.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        assertThat(sent.getCcls()).isEmpty();
    }

    @Test
    void gameDetected_ignoredOffline_readsNoSettings() {
        binding.setIgnored(true);
        live(false);

        assertThat(chain.decide(user, new GameDetected(binding)))
                .isEqualTo(CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.OFFLINE));
        verifyNoInteractions(settingsRepository);
    }

    @Test
    void gameDetected_incompleteLive_updatesTheFallback() {
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        withSettings();
        live(true);

        CategoryDecision decision = chain.decide(user, new GameDetected(binding));

        assertThat(decision.action()).isEqualTo(Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.INCOMPLETE_FALLBACK);
        assertThat(decision.binding().getSourceName()).isEqualTo("incomplete-fallback");
        assertThat(decision.binding().getTwitchGameId()).isEqualTo("1469308723");
    }

    @Test
    void gameDetected_incompleteOffline_storesTheFallback() {
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        withSettings();
        live(false);

        CategoryDecision decision = chain.decide(user, new GameDetected(binding));

        assertThat(decision.action()).isEqualTo(Action.STORE_PENDING);
        assertThat(decision.rule()).isEqualTo(CategoryRule.INCOMPLETE_FALLBACK);
        assertThat(decision.reason()).isEqualTo(Reason.NONE);
        GameBinding stored = decision.binding();
        assertThat(stored.getId()).isNull();
        assertThat(stored.getSourceType()).isEqualTo(GameBinding.SourceType.MANUAL);
        assertThat(stored.getSourceName()).isEqualTo("incomplete-fallback");
        assertThat(stored.getTwitchGameId()).isEqualTo("1469308723");
        assertThat(stored.getTwitchGameName()).isEqualTo("Software and Game Development");
        assertThat(stored.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        assertThat(stored.getCcls()).isEqualTo(Set.of("ProfanityVulgarity"));
    }

    @Test
    void gameDetected_incompleteUnconfigured() {
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        settings.setIncompleteFallbackTwitchGameId(" ");
        withSettings();

        assertThat(chain.decide(user, new GameDetected(binding)))
                .isEqualTo(CategoryDecision.none(CategoryRule.INCOMPLETE_FALLBACK, Reason.NOTHING_CONFIGURED));
        verifyNoInteractions(streamStateService);
    }

    @Test
    void gameDetected_incompleteNoSettings() {
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new GameDetected(binding)))
                .isEqualTo(CategoryDecision.none(CategoryRule.INCOMPLETE_FALLBACK, Reason.NO_SETTINGS));
    }

    @Test
    void gameDetected_neverReadsTheCustomBinding() {
        live(true);

        chain.decide(user, new GameDetected(binding));

        verifyNoInteractions(activations);
    }

    // ---- GameClosed ----

    @Test
    void gameClosed_customBinding() {
        activeCustom(false);

        assertThat(chain.decide(user, new GameClosed()))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE));
        verifyNoInteractions(streamStateService, settingsRepository);
    }

    @Test
    void gameClosed_offline() {
        live(false);

        assertThat(chain.decide(user, new GameClosed()))
                .isEqualTo(CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.OFFLINE));
        verifyNoInteractions(settingsRepository);
    }

    @Test
    void gameClosed_hold() {
        live(true);
        hold.hold(user);

        assertThat(chain.decide(user, new GameClosed()))
                .isEqualTo(CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.RAID_HOLD));
        verifyNoInteractions(settingsRepository);
    }

    @Test
    void gameClosed_noSettings() {
        live(true);
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new GameClosed()))
                .isEqualTo(CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.NO_SETTINGS));
    }

    @Test
    void gameClosed_off() {
        live(true);
        settings.setApplyDefaultOnNoGame(false);
        withSettings();

        assertThat(chain.decide(user, new GameClosed()))
                .isEqualTo(CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.NO_GAME_FALLBACK_OFF));
    }

    @Test
    void gameClosed_noId() {
        live(true);
        settings.setNoGameTwitchGameId(null);
        withSettings();

        assertThat(chain.decide(user, new GameClosed()))
                .isEqualTo(CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.NOTHING_CONFIGURED));
    }

    @Test
    void gameClosed_holdTakenMeanwhile() {
        live(true);
        // A raid takes the hold while the settings are read, between the two checks
        when(settingsRepository.findById(user.getId())).thenAnswer(inv -> {
            hold.hold(user);
            return Optional.of(settings);
        });

        assertThat(chain.decide(user, new GameClosed()))
                .isEqualTo(CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.RAID_HOLD));
    }

    @Test
    void gameClosed_live_updatesTheNoGameFallback() {
        live(true);
        withSettings();

        CategoryDecision decision = chain.decide(user, new GameClosed());

        assertThat(decision.action()).isEqualTo(Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.NO_GAME_FALLBACK);
        assertThat(decision.binding().getSourceName()).isEqualTo(CategoryChain.NO_GAME_FALLBACK_SOURCE);
    }

    // ---- NoGameAfterCustomBinding ----

    @Test
    void noGameAfterCustomBinding_neverReadsTheCustomBinding() {
        live(true);
        withSettings();

        CategoryDecision decision = chain.decide(user, new NoGameAfterCustomBinding());

        assertThat(decision.action()).isEqualTo(Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.NO_GAME_FALLBACK);
        verifyNoInteractions(activations);
    }

    // ---- apply ----

    @Test
    void apply_appliesTheDecision() {
        live(true);
        CategoryOutcome applied = CategoryOutcome.applied(CategoryRule.GAME_BINDING);
        when(applier.apply(user, CategoryDecision.update(CategoryRule.GAME_BINDING, binding))).thenReturn(applied);

        assertThat(chain.apply(user, new GameDetected(binding))).isSameAs(applied);
        verify(applier).apply(user, CategoryDecision.update(CategoryRule.GAME_BINDING, binding));
    }

    // ---- detectionGuard ----

    @Test
    void detectionGuard_none() {
        when(activations.active(user)).thenReturn(Optional.empty());

        assertThat(chain.detectionGuard(user, false)).isEqualTo(DetectionGuard.NO_CUSTOM_BINDING);
    }

    @Test
    void detectionGuard_uninterruptible_keeps() {
        activeCustom(true);

        assertThat(chain.detectionGuard(user, false)).isEqualTo(DetectionGuard.KEEPS_CUSTOM_BINDING);
    }

    @Test
    void detectionGuard_firstSinceStart_keeps() {
        activeCustom(false);

        assertThat(chain.detectionGuard(user, true)).isEqualTo(DetectionGuard.KEEPS_CUSTOM_BINDING);
    }

    @Test
    void detectionGuard_otherwise_interrupts() {
        activeCustom(false);

        assertThat(chain.detectionGuard(user, false)).isEqualTo(DetectionGuard.INTERRUPTS_CUSTOM_BINDING);
    }
}
