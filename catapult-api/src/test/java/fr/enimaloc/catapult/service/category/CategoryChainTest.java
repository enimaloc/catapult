package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryChain.DetectionGuard;
import fr.enimaloc.catapult.service.category.CategoryDecision.Action;
import fr.enimaloc.catapult.service.category.CategoryOutcome.Reason;
import fr.enimaloc.catapult.service.category.CategoryTrigger.BindingEdited;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameDetected;
import fr.enimaloc.catapult.service.category.CategoryTrigger.CustomBindingTriggered;
import fr.enimaloc.catapult.service.category.CategoryTrigger.ManualCategory;
import fr.enimaloc.catapult.service.category.CategoryTrigger.MappingChanged;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameSettingsSaved;
import fr.enimaloc.catapult.service.category.CategoryTrigger.RaidedOut;
import fr.enimaloc.catapult.service.category.CategoryTrigger.RevertCategory;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameAfterCustomBinding;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamEnded;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link CategoryChain#decide} for the detection family, one test per rule outcome, and its detection guard. */
@ExtendWith(MockitoExtension.class)
class CategoryChainTest {

    @Mock private CustomBindingActivations activations;
    @Mock private StreamStateService streamStateService;
    @Mock private UserSettingsRepository settingsRepository;
    @Mock private GameStateService gameStateService;
    @Mock private UserAccountRepository accountRepository;
    @Mock private CategoryApplier applier;
    @Mock private RaidRestoreGateService gate;
    @Mock private GameBindingLookup lookup;

    private final RaidRestoreHold hold = new RaidRestoreHold();
    private CategoryChain chain;
    private UserAccount user;
    private UserSettings settings;
    private GameBinding binding;

    @BeforeEach
    void setUp() {
        chain = new CategoryChain(activations, streamStateService, settingsRepository, hold, gameStateService,
                accountRepository, applier, gate, lookup);
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
        custom.setTwitchGameId("509660");
        custom.setTwitchGameName("Art");
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

    // ---- StreamStarted ----

    @Test
    void streamStarted_customBinding() {
        activeCustom(false);

        CategoryDecision decision = chain.decide(user, new StreamStarted());

        assertThat(decision.action()).isEqualTo(Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.CUSTOM_BINDING);
        assertThat(decision.binding().getSourceName()).isEqualTo("custom:Art");
        assertThat(decision.binding().getTwitchGameId()).isEqualTo("509660");
        assertThat(decision.binding().getUser()).isSameAs(user);
        verifyNoInteractions(settingsRepository, streamStateService);
    }

    @Test
    void streamStarted_default() {
        when(activations.active(user)).thenReturn(Optional.empty());
        settings.setApplyDefaultOnStreamStart(true);
        withSettings();

        assertThat(chain.decide(user, new StreamStarted()))
                .isEqualTo(CategoryDecision.reset(CategoryRule.DEFAULT_CATEGORY));
        verify(streamStateService, never()).getPending(user);
    }

    @Test
    void streamStarted_pending() {
        settings.setApplyDefaultOnStreamStart(false);
        when(activations.active(user)).thenReturn(Optional.empty());
        withSettings();
        when(streamStateService.getPending(user)).thenReturn(Optional.of(binding));

        assertThat(chain.decide(user, new StreamStarted()))
                .isEqualTo(CategoryDecision.update(CategoryRule.PENDING, binding));
    }

    @Test
    void streamStarted_noSettings_stillUsesThePending() {
        when(activations.active(user)).thenReturn(Optional.empty());
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.empty());
        when(streamStateService.getPending(user)).thenReturn(Optional.of(binding));

        assertThat(chain.decide(user, new StreamStarted()))
                .isEqualTo(CategoryDecision.update(CategoryRule.PENDING, binding));
    }

    @Test
    void streamStarted_nothing() {
        settings.setApplyDefaultOnStreamStart(false);
        when(activations.active(user)).thenReturn(Optional.empty());
        withSettings();
        when(streamStateService.getPending(user)).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new StreamStarted()))
                .isEqualTo(CategoryDecision.none(CategoryRule.PENDING, Reason.NOTHING_PENDING));
    }

    // ---- StreamEnded ----

    @Test
    void streamEnded_default() {
        settings.setApplyDefaultOnStreamEnd(true);
        withSettings();

        assertThat(chain.decide(user, new StreamEnded()))
                .isEqualTo(CategoryDecision.reset(CategoryRule.DEFAULT_CATEGORY));
    }

    @Test
    void streamEnded_off() {
        settings.setApplyDefaultOnStreamEnd(false);
        withSettings();

        assertThat(chain.decide(user, new StreamEnded()))
                .isEqualTo(CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.DEFAULT_OFF));
    }

    @Test
    void streamEnded_noSettings() {
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new StreamEnded()))
                .isEqualTo(CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.DEFAULT_OFF));
    }

    @Test
    void streamEnded_neverReadsTheCustomBinding() {
        settings.setApplyDefaultOnStreamEnd(true);
        withSettings();

        chain.decide(user, new StreamEnded());

        verifyNoInteractions(activations);
    }

    // ---- BindingEdited ----

    private void playing(boolean playing) {
        when(gameStateService.getLastKnownGame(user)).thenReturn(playing
                ? Optional.of(new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2"))
                : Optional.of(new DetectedGame("730", GameBinding.SourceType.STEAM, "CS2")));
    }

    @Test
    void bindingEdited_played_updatesItAsIs() {
        playing(true);
        when(activations.active(user)).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new BindingEdited(binding)))
                .isEqualTo(CategoryDecision.update(CategoryRule.GAME_BINDING, binding));
        verifyNoInteractions(streamStateService);
    }

    @Test
    void bindingEdited_playedIgnoredOrIncomplete_isStillUpdatedAsIs() {
        playing(true);
        when(activations.active(user)).thenReturn(Optional.empty());
        binding.setIgnored(true);
        assertThat(chain.decide(user, new BindingEdited(binding)))
                .isEqualTo(CategoryDecision.update(CategoryRule.GAME_BINDING, binding));

        binding.setIgnored(false);
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        assertThat(chain.decide(user, new BindingEdited(binding)))
                .isEqualTo(CategoryDecision.update(CategoryRule.GAME_BINDING, binding));
        verifyNoInteractions(streamStateService, settingsRepository);
    }

    @Test
    void bindingEdited_notPlayed() {
        playing(false);

        assertThat(chain.decide(user, new BindingEdited(binding)))
                .isEqualTo(CategoryDecision.none(CategoryRule.GAME_BINDING, Reason.NOT_PLAYED_NOW));
        verifyNoInteractions(activations);
    }

    @Test
    void bindingEdited_nothingPlaying_isNotPlayed() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new BindingEdited(binding)).reason()).isEqualTo(Reason.NOT_PLAYED_NOW);
        verifyNoInteractions(activations);
    }

    @Test
    void bindingEdited_customBinding() {
        playing(true);
        activeCustom(false);

        assertThat(chain.decide(user, new BindingEdited(binding)))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE));
        verifyNoInteractions(streamStateService);
    }

    // ---- MappingChanged ----

    @Test
    void mappingChanged_customBinding() {
        activeCustom(false);

        assertThat(chain.decide(user, new MappingChanged(binding)))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE));
        verifyNoInteractions(streamStateService);
    }

    @Test
    void mappingChanged_offline() {
        when(activations.active(user)).thenReturn(Optional.empty());
        live(false);

        assertThat(chain.decide(user, new MappingChanged(binding)))
                .isEqualTo(CategoryDecision.none(CategoryRule.GAME_BINDING, Reason.OFFLINE));
    }

    @Test
    void mappingChanged_ignored_isTheNoGameDecision() {
        when(activations.active(user)).thenReturn(Optional.empty());
        binding.setIgnored(true);
        live(true);
        withSettings();
        settings.setApplyDefaultOnNoGame(true);

        CategoryDecision decision = chain.decide(user, new MappingChanged(binding));

        assertThat(decision.action()).isEqualTo(Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.NO_GAME_FALLBACK);
        assertThat(decision.binding().getSourceName()).isEqualTo(CategoryChain.NO_GAME_FALLBACK_SOURCE);
    }

    @Test
    void mappingChanged_incompleteLive_updatesTheFallback() {
        when(activations.active(user)).thenReturn(Optional.empty());
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        live(true);
        withSettings();

        CategoryDecision decision = chain.decide(user, new MappingChanged(binding));

        assertThat(decision.action()).isEqualTo(Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.INCOMPLETE_FALLBACK);
        assertThat(decision.binding().getSourceName()).isEqualTo(CategoryChain.INCOMPLETE_FALLBACK_SOURCE);
    }

    @Test
    void mappingChanged_incompleteOffline() {
        when(activations.active(user)).thenReturn(Optional.empty());
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        live(false);

        CategoryDecision decision = chain.decide(user, new MappingChanged(binding));

        // The offline check comes first, as the detection's own: the settings are never read
        assertThat(decision).isEqualTo(CategoryDecision.none(CategoryRule.GAME_BINDING, Reason.OFFLINE));
        verifyNoInteractions(settingsRepository);
        assertThat(decision.action()).isNotEqualTo(Action.STORE_PENDING);
    }

    @Test
    void mappingChanged_incompleteLiveWithoutFallback() {
        when(activations.active(user)).thenReturn(Optional.empty());
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        live(true);
        settings.setIncompleteFallbackTwitchGameId(null);
        withSettings();

        assertThat(chain.decide(user, new MappingChanged(binding))).isEqualTo(
                CategoryDecision.none(CategoryRule.INCOMPLETE_FALLBACK, Reason.NOTHING_CONFIGURED));
    }

    @Test
    void mappingChanged_live() {
        when(activations.active(user)).thenReturn(Optional.empty());
        live(true);

        assertThat(chain.decide(user, new MappingChanged(binding)))
                .isEqualTo(CategoryDecision.update(CategoryRule.GAME_BINDING, binding));
    }

    // ---- CustomBindingTriggered ----

    private CustomBinding art() {
        CustomBinding art = new CustomBinding();
        art.setId(UUID.randomUUID());
        art.setUser(user);
        art.setName("Art");
        art.setTwitchGameId("509660");
        art.setTwitchGameName("Art");
        return art;
    }

    private UserAccount account(boolean botEnabled, UserAccount.Status status) {
        UserAccount account = new UserAccount();
        account.setId(user.getId());
        account.setBotEnabled(botEnabled);
        account.setStatus(status);
        return account;
    }

    @Test
    void customBindingTriggered_botOff() {
        when(accountRepository.findById(user.getId()))
                .thenReturn(Optional.of(account(false, UserAccount.Status.ACTIVE)));

        assertThat(chain.decide(user, new CustomBindingTriggered(art())))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.BOT_INACTIVE));
    }

    @Test
    void customBindingTriggered_accountInactive() {
        when(accountRepository.findById(user.getId()))
                .thenReturn(Optional.of(account(true, UserAccount.Status.INACTIVE)));

        assertThat(chain.decide(user, new CustomBindingTriggered(art())))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.BOT_INACTIVE));
    }

    @Test
    void customBindingTriggered_accountMissing() {
        when(accountRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new CustomBindingTriggered(art())))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.BOT_INACTIVE));
    }

    @Test
    void customBindingTriggered_offline_updatesIt() {
        when(accountRepository.findById(user.getId()))
                .thenReturn(Optional.of(account(true, UserAccount.Status.ACTIVE)));

        CategoryDecision decision = chain.decide(user, new CustomBindingTriggered(art()));

        assertThat(decision.action()).isEqualTo(CategoryDecision.Action.UPDATE_CHANNEL);
        assertThat(decision.rule()).isEqualTo(CategoryRule.CUSTOM_BINDING);
        assertThat(decision.binding().getTwitchGameId()).isEqualTo("509660");
        assertThat(decision.binding().getSourceName()).startsWith(CustomBinding.SOURCE_NAME_PREFIX);
        verify(streamStateService, never()).isLive(any());
    }

    // ---- ManualCategory, RevertCategory, NoGameSettingsSaved ----

    @Test
    void manualCategory_alwaysUpdates() {
        assertThat(chain.decide(user, new ManualCategory(binding)))
                .isEqualTo(CategoryDecision.update(CategoryRule.MANUAL_CHOICE, binding));

        verify(streamStateService, never()).isLive(any());
        verifyNoInteractions(activations);
    }

    @Test
    void revertCategory_alwaysSets() {
        assertThat(chain.decide(user, new RevertCategory("509658", "Just Chatting")))
                .isEqualTo(CategoryDecision.setCategory(CategoryRule.MANUAL_CHOICE, "509658", "Just Chatting"));

        verify(streamStateService, never()).isLive(any());
        verifyNoInteractions(activations);
    }

    @Test
    void noGameSettingsSaved_nothingDetected() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());
        when(activations.active(user)).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new NoGameSettingsSaved()))
                .isEqualTo(CategoryDecision.reset(CategoryRule.DEFAULT_CATEGORY));

        verify(streamStateService, never()).isLive(any());
        verifyNoInteractions(settingsRepository);
    }

    @Test
    void noGameSettingsSaved_nothingDetected_ignoresTheRaidHold() {
        hold.hold(user);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());
        when(activations.active(user)).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new NoGameSettingsSaved()).action()).isEqualTo(Action.RESET_TO_DEFAULT);
    }

    @Test
    void noGameSettingsSaved_gameDetected() {
        when(gameStateService.getLastKnownGame(user))
                .thenReturn(Optional.of(new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2")));

        assertThat(chain.decide(user, new NoGameSettingsSaved()))
                .isEqualTo(CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.GAME_DETECTED));

        verifyNoInteractions(activations);
    }

    @Test
    void noGameSettingsSaved_customBinding() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());
        activeCustom(false);

        assertThat(chain.decide(user, new NoGameSettingsSaved()))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE));
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

    // ---- RaidedOut ----

    private final DetectedGame played = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");

    private void raidSetUp() {
        settings.setRaidRestoreGameMinutes(10);
        withSettings();
        when(gate.isAvailableReadOnly(user)).thenReturn(true);
        when(gameStateService.recentlyDetectedGame(user, Duration.ofMinutes(10))).thenReturn(Optional.of(played));
        lenient().when(lookup.findByDetectedGame(user, played)).thenReturn(Optional.of(binding));
    }

    @Test
    void raidedOut_settingOff() {
        settings.setRaidRestoreGameMinutes(null);
        withSettings();

        assertThat(chain.decide(user, new RaidedOut("1")))
                .isEqualTo(CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.RAID_RESTORE_OFF));
        verifyNoInteractions(gate);
    }

    @Test
    void raidedOut_noSettings() {
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new RaidedOut("1")))
                .isEqualTo(CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.RAID_RESTORE_OFF));
        verifyNoInteractions(gate);
    }

    @Test
    void raidedOut_underOneMinute() {
        settings.setRaidRestoreGameMinutes(0);
        withSettings();

        assertThat(chain.decide(user, new RaidedOut("1")))
                .isEqualTo(CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.RAID_RESTORE_OFF));
        verifyNoInteractions(gate);
    }

    @Test
    void raidedOut_gateClosed() {
        settings.setRaidRestoreGameMinutes(10);
        withSettings();
        when(gate.isAvailableReadOnly(user)).thenReturn(false);

        assertThat(chain.decide(user, new RaidedOut("1")))
                .isEqualTo(CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.RAID_RESTORE_OFF));
        verify(gate, never()).isAvailableFor(any());
    }

    @Test
    void raidedOut_customBinding() {
        settings.setRaidRestoreGameMinutes(10);
        withSettings();
        when(gate.isAvailableReadOnly(user)).thenReturn(true);
        activeCustom(false);

        assertThat(chain.decide(user, new RaidedOut("1")))
                .isEqualTo(CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE));
        verifyNoInteractions(gameStateService);
    }

    @Test
    void raidedOut_nothingRecent() {
        raidSetUp();
        when(gameStateService.recentlyDetectedGame(user, Duration.ofMinutes(10))).thenReturn(Optional.empty());

        assertThat(chain.decide(user, new RaidedOut("1")))
                .isEqualTo(CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.NOTHING_TO_RESTORE));
    }

    @Test
    void raidedOut_ignoredIncompleteOrWithoutTwitchId_nothingToRestore() {
        raidSetUp();
        CategoryDecision expected = CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.NOTHING_TO_RESTORE);

        binding.setIgnored(true);
        assertThat(chain.decide(user, new RaidedOut("1"))).isEqualTo(expected);
        binding.setIgnored(false);
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        assertThat(chain.decide(user, new RaidedOut("1"))).isEqualTo(expected);
        binding.setStatus(GameBinding.Status.AUTO);
        binding.setTwitchGameId(" ");
        assertThat(chain.decide(user, new RaidedOut("1"))).isEqualTo(expected);
    }

    @Test
    void raidedOut_alreadyOnIt() {
        raidSetUp();

        assertThat(chain.decide(user, new RaidedOut("29595")))
                .isEqualTo(CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.ALREADY_ON_CATEGORY));
    }

    @Test
    void raidedOut_restores() {
        raidSetUp();

        assertThat(chain.decide(user, new RaidedOut("509658")))
                .isEqualTo(CategoryDecision.update(CategoryRule.RAID_RESTORE, binding));
        assertThat(chain.decide(user, new RaidedOut(null)))
                .isEqualTo(CategoryDecision.update(CategoryRule.RAID_RESTORE, binding));
        verify(streamStateService, never()).isLive(any());
    }

    @Test
    void raidedOut_decidingTakesNoHold() {
        raidSetUp();

        chain.decide(user, new RaidedOut("509658"));
        chain.decide(user, new RaidedOut("29595"));

        assertThat(hold.isHolding(user)).isFalse();
    }
}
