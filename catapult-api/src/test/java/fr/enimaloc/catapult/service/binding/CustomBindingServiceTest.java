package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.listener.GameEventListener;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomBindingServiceTest {

    @Mock private CustomBindingRepository bindings;
    @Mock private CustomBindingActivations activations;
    @Mock private TwitchService twitchService;
    @Mock private ExperimentService experimentService;
    @Mock private GameStateService gameStateService;
    @Mock private StreamStateService streamStateService;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private GameEventListener gameEventListener;
    @Mock private ChannelEventPublisher channelEventPublisher;

    @InjectMocks private CustomBindingService service;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setBotEnabled(true);
        user.setStatus(UserAccount.Status.ACTIVE);
        lenient().when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user));
        lenient().when(experimentService.evaluateGate(user, CustomBindingService.EXPERIMENT_KEY)).thenReturn(true);
        lenient().when(bindings.save(any(CustomBinding.class))).thenAnswer(inv -> {
            CustomBinding b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return b;
        });
    }

    private static CustomBindingRequest request(String name) {
        return new CustomBindingRequest(name, "509660", "Art", Set.of("ProfanityVulgarity"), Set.of("chill"), false);
    }

    private CustomBinding binding(String name) {
        CustomBinding b = new CustomBinding();
        b.setId(UUID.randomUUID());
        b.setUser(user);
        b.setName(name);
        b.setTwitchGameId("509660");
        b.setTwitchGameName("Art");
        b.getCcls().add("ProfanityVulgarity");
        b.getTws().add("chill");
        return b;
    }

    private static void assertStatus(Executable call, HttpStatus status) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    private static void assertCode(Executable call, String code) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(CustomBindingException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void aRefusedSave_carriesItsCode() {
        assertCode(() -> service.create(user, request("   ")), "invalid");
        assertCode(() -> service.create(user, new CustomBindingRequest("Art", " ", "Art", null, null, false)), "invalid");
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Test
    void create_trimsName_andRejectsDuplicateIgnoringCase() {
        when(bindings.findByUserAndNameIgnoreCase(user, "Art")).thenReturn(Optional.empty());

        CustomBinding created = service.create(user, request("  Art  "));

        assertThat(created.getName()).isEqualTo("Art");
        assertThat(created.getUser()).isSameAs(user);
        assertThat(created.getTwitchGameId()).isEqualTo("509660");
        assertThat(created.getTwitchGameName()).isEqualTo("Art");
        assertThat(created.getCcls()).containsExactly("ProfanityVulgarity");
        assertThat(created.getTws()).containsExactly("chill");
        assertThat(created.isUninterruptible()).isFalse();
        verify(channelEventPublisher).customBindingsUpdated(user.getId());

        CustomBinding existing = binding("Art");
        when(bindings.findByUserAndNameIgnoreCase(user, "ART")).thenReturn(Optional.of(existing));
        assertStatus(() -> service.create(user, request(" ART ")), HttpStatus.CONFLICT);
        verify(bindings, times(1)).save(any(CustomBinding.class));
    }

    @Test
    void create_rejectsABlankOrTooLongName_andAMissingCategory() {
        assertStatus(() -> service.create(user, request("   ")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(user, request(null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(user, request("x".repeat(41))), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(user,
                new CustomBindingRequest("Art", " ", "Art", null, null, false)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(user,
                new CustomBindingRequest("Art", "509660", null, null, null, false)), HttpStatus.BAD_REQUEST);
        verify(bindings, never()).save(any());
    }

    @Test
    void create_withoutCclsOrTws_keepsThemEmpty_and40CharactersIsAllowed() {
        String name = "x".repeat(40);
        when(bindings.findByUserAndNameIgnoreCase(user, name)).thenReturn(Optional.empty());

        CustomBinding created = service.create(user, new CustomBindingRequest(name, "509660", "Art", null, null, true));

        assertThat(created.getCcls()).isEmpty();
        assertThat(created.getTws()).isEmpty();
        assertThat(created.isUninterruptible()).isTrue();
    }

    @Test
    void create_21st_isRejected() {
        when(bindings.countByUser(user)).thenReturn(20L);

        assertStatus(() -> service.create(user, request("Art")), HttpStatus.CONFLICT);
        verify(bindings, never()).save(any());
    }

    @Test
    void update_rejectsAnotherOnesName_butKeepsItsOwn() {
        CustomBinding art = binding("Art");
        CustomBinding dev = binding("Dev");
        when(bindings.findByIdAndUser(art.getId(), user)).thenReturn(Optional.of(art));
        when(bindings.findByUserAndNameIgnoreCase(user, "dev")).thenReturn(Optional.of(dev));
        assertStatus(() -> service.update(user, art.getId(), request("dev")), HttpStatus.CONFLICT);

        when(bindings.findByUserAndNameIgnoreCase(user, "ART")).thenReturn(Optional.of(art));
        CustomBindingService.UpdateResult result = service.update(user, art.getId(),
                new CustomBindingRequest("ART", "1469308723", "Software and Game Development", Set.of(), Set.of(), true));
        CustomBinding updated = result.binding();

        assertThat(updated.getName()).isEqualTo("ART");
        assertThat(updated.getTwitchGameId()).isEqualTo("1469308723");
        assertThat(updated.getCcls()).isEmpty();
        assertThat(updated.isUninterruptible()).isTrue();
        verify(channelEventPublisher).customBindingsUpdated(user.getId());
        // Not the active one: saved only
        assertThat(result.reapplied()).isNull();
        verifyNoInteractions(twitchService);
    }

    @Test
    void updateActive_isSaved_thenReappliedToTwitch_andStaysActiveWhateverTwitchSays() {
        CustomBinding art = binding("Art");
        when(bindings.findByIdAndUser(art.getId(), user)).thenReturn(Optional.of(art));
        when(bindings.findByUserAndNameIgnoreCase(user, "Art")).thenReturn(Optional.of(art));
        givenActive(art);
        when(twitchService.updateChannelReporting(eq(user), any()))
                .thenReturn(ChannelUpdateOutcome.APPLIED, ChannelUpdateOutcome.FAILED);
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
                .thenReturn(Optional.of(new OAuthToken()));
        CustomBindingRequest dev = new CustomBindingRequest("Art", "1469308723", "Software and Game Development",
                Set.of(), Set.of(), false);

        assertThat(service.update(user, art.getId(), dev).reapplied()).isEqualTo(ActivationResult.APPLIED);
        assertThat(service.update(user, art.getId(), dev).reapplied()).isEqualTo(ActivationResult.FAILED);

        ArgumentCaptor<GameBinding> applied = ArgumentCaptor.forClass(GameBinding.class);
        InOrder order = inOrder(bindings, twitchService);
        order.verify(bindings).save(art);
        order.verify(twitchService).updateChannelReporting(eq(user), applied.capture());
        assertThat(applied.getValue().getTwitchGameId()).isEqualTo("1469308723");
        verify(bindings, times(2)).save(art);
        // Kept active either way: neither re-recorded nor cleared
        verify(activations, never()).record(any(), any());
        verify(activations, never()).clear(any());
    }

    @Test
    void save_onlyTheUniqueNameViolation_isANameConflict() {
        when(bindings.findByUserAndNameIgnoreCase(user, "Art")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("dup", new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key", new java.sql.SQLException("duplicate key"), "uq_custom_binding_user_name")))
                .doThrow(new DataIntegrityViolationException("fk", new org.hibernate.exception.ConstraintViolationException(
                        "fk", new java.sql.SQLException("fk"), "custom_binding_user_id_fkey")))
                .when(bindings).save(any(CustomBinding.class));

        assertStatus(() -> service.create(user, request("Art")), HttpStatus.CONFLICT);
        assertThatThrownBy(() -> service.create(user, request("Art"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_unknown_isNotFound() {
        UUID id = UUID.randomUUID();
        when(bindings.findByIdAndUser(id, user)).thenReturn(Optional.empty());

        assertStatus(() -> service.update(user, id, request("Art")), HttpStatus.NOT_FOUND);
    }

    @Test
    void resolve_byIdOrByNameIgnoringCase() {
        CustomBinding art = binding("Art");
        when(bindings.findByIdAndUser(art.getId(), user)).thenReturn(Optional.of(art));
        when(bindings.findByUserAndNameIgnoreCase(user, "art")).thenReturn(Optional.of(art));
        when(bindings.findByUserAndNameIgnoreCase(user, "nope")).thenReturn(Optional.empty());

        assertThat(service.resolve(user, art.getId().toString())).contains(art);
        assertThat(service.resolve(user, " art ")).contains(art);
        assertThat(service.resolve(user, "nope")).isEmpty();
    }

    @Test
    void list_returnsTheStreamersBindings() {
        CustomBinding art = binding("Art");
        when(bindings.findByUserOrderByCreatedAtAsc(user)).thenReturn(List.of(art));

        assertThat(service.list(user)).containsExactly(art);
    }

    // ── Activation ───────────────────────────────────────────────────────────

    @Test
    void activate_appliesCategoryEvenOffline_andRecordsActivation() {
        CustomBinding art = binding("Art");
        lenient().when(streamStateService.isLive(user)).thenReturn(false);
        when(twitchService.updateChannelReporting(eq(user), any())).thenReturn(ChannelUpdateOutcome.APPLIED);

        assertThat(service.activate(user, art)).isEqualTo(ActivationResult.APPLIED);

        ArgumentCaptor<GameBinding> applied = ArgumentCaptor.forClass(GameBinding.class);
        verify(twitchService).updateChannelReporting(eq(user), applied.capture());
        GameBinding transientBinding = applied.getValue();
        assertThat(transientBinding.getId()).isNull();
        assertThat(transientBinding.getUser()).isSameAs(user);
        assertThat(transientBinding.getSourceType()).isEqualTo(GameBinding.SourceType.MANUAL);
        assertThat(transientBinding.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        assertThat(transientBinding.getSourceName()).isEqualTo("custom:Art");
        assertThat(transientBinding.getTwitchGameId()).isEqualTo("509660");
        assertThat(transientBinding.getTwitchGameName()).isEqualTo("Art");
        assertThat(transientBinding.getCcls()).containsExactly("ProfanityVulgarity");
        assertThat(transientBinding.getTws()).containsExactly("chill");

        verify(activations).record(user, art);
        verify(streamStateService, never()).storePending(any(), any());
    }

    @Test
    void activate_another_replacesTheActiveOne() {
        CustomBinding art = binding("Art");
        CustomBinding dev = binding("Dev");
        when(twitchService.updateChannelReporting(eq(user), any())).thenReturn(ChannelUpdateOutcome.APPLIED);

        service.activate(user, art);
        service.activate(user, dev);

        InOrder order = inOrder(activations);
        order.verify(activations).record(user, art);
        order.verify(activations).record(user, dev);
        verify(activations, never()).clear(any());
        // Replacing is not a deactivation: the open game does not get a turn in between
        verifyNoInteractions(gameEventListener);
    }

    @Test
    void reactivate_sameOne_reappliesWithoutError() {
        CustomBinding art = binding("Art");
        lenient().when(activations.active(user))
                .thenReturn(Optional.of(new CustomBindingActivation(user.getId(), art, java.time.Instant.EPOCH)));
        when(twitchService.updateChannelReporting(eq(user), any())).thenReturn(ChannelUpdateOutcome.APPLIED);

        assertThat(service.activate(user, art)).isEqualTo(ActivationResult.APPLIED);

        verify(twitchService).updateChannelReporting(eq(user), any());
        verify(activations).record(user, art);
    }

    @Test
    void twitchFailure_recordsNothing() {
        CustomBinding art = binding("Art");
        OAuthToken token = new OAuthToken();
        lenient().when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
                .thenReturn(Optional.of(token));

        // Twitch refused or failed the update, the token still being there and the bot still on
        when(twitchService.updateChannelReporting(eq(user), any())).thenReturn(ChannelUpdateOutcome.FAILED);
        assertThat(service.activate(user, art)).isEqualTo(ActivationResult.FAILED);

        // No Twitch token at all
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());
        assertThat(service.activate(user, art)).isEqualTo(ActivationResult.TWITCH_UNAVAILABLE);

        // The token was refused during the call: TwitchServiceImpl paused the bot while failing
        UserAccount paused = new UserAccount();
        paused.setId(user.getId());
        paused.setBotEnabled(false);
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user), Optional.of(paused));
        assertThat(service.activate(user, art)).isEqualTo(ActivationResult.TWITCH_UNAVAILABLE);

        verify(activations, never()).record(any(), any());
    }

    @Test
    void activate_botPaused_isBotDisabled_withoutCallingTwitch() {
        UserAccount paused = new UserAccount();
        paused.setId(user.getId());
        paused.setBotEnabled(false);
        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(paused));

        assertThat(service.activate(user, binding("Art"))).isEqualTo(ActivationResult.BOT_DISABLED);

        verifyNoInteractions(twitchService, oAuthTokenRepository);
        verify(activations, never()).record(any(), any());
    }

    @Test
    void activate_accountNotActive_isBotDisabled_withoutCallingTwitch() {
        UserAccount leaving = new UserAccount();
        leaving.setId(user.getId());
        leaving.setBotEnabled(true);
        leaving.setStatus(UserAccount.Status.PENDING_DELETION);
        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(leaving));

        assertThat(service.activate(user, binding("Art"))).isEqualTo(ActivationResult.BOT_DISABLED);

        verifyNoInteractions(twitchService);
        verify(activations, never()).record(any(), any());
    }

    @Test
    void activate_botPausedBetweenTheCheckAndTheCall_isBotDisabled() {
        when(twitchService.updateChannelReporting(eq(user), any())).thenReturn(ChannelUpdateOutcome.SKIPPED);

        assertThat(service.activate(user, binding("Art"))).isEqualTo(ActivationResult.BOT_DISABLED);
        verify(activations, never()).record(any(), any());
    }

    @Test
    void activate_someoneElsesBinding_isNotFound() {
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());
        CustomBinding theirs = binding("Art");
        theirs.setUser(other);

        assertStatus(() -> service.activate(user, theirs), HttpStatus.NOT_FOUND);
        verifyNoInteractions(twitchService);
    }

    @Test
    void active_returnsTheRecordedActivation() {
        CustomBindingActivation activation = new CustomBindingActivation(user.getId(), binding("Art"), java.time.Instant.EPOCH);
        when(activations.active(user)).thenReturn(Optional.of(activation));

        assertThat(service.active(user)).contains(activation);
    }

    // ── Deactivation ─────────────────────────────────────────────────────────

    private void givenActive(CustomBinding binding) {
        lenient().when(activations.active(user))
                .thenReturn(Optional.of(new CustomBindingActivation(user.getId(), binding, java.time.Instant.EPOCH)));
    }

    @Test
    void deactivate_live_appliesTheOpenGame() {
        givenActive(binding("Art"));
        DetectedGame game = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(streamStateService.isLive(user)).thenReturn(true);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(game));

        service.deactivate(user);

        InOrder order = inOrder(activations, gameEventListener);
        order.verify(activations).clear(user);
        order.verify(gameEventListener).applyDetectedGame(user, game);
        verify(gameEventListener, never()).applyNoGameFallback(any());
        verify(twitchService, never()).resetToDefault(any());
    }

    @Test
    void deactivate_live_noGame_appliesNoGameFallback() {
        givenActive(binding("Art"));
        when(streamStateService.isLive(user)).thenReturn(true);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        service.deactivate(user);

        verify(activations).clear(user);
        verify(gameEventListener).applyNoGameFallback(user);
        verify(gameEventListener, never()).applyDetectedGame(any(), any());
        verify(twitchService, never()).resetToDefault(any());
    }

    @Test
    void deactivate_offline_storesOpenGamePending_andAppliesStreamEndChoice() {
        givenActive(binding("Art"));
        DetectedGame game = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(streamStateService.isLive(user)).thenReturn(false);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(game));
        UserSettings settings = new UserSettings();
        settings.setApplyDefaultOnStreamEnd(true);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        service.deactivate(user);

        // Offline, a detection stores the game's binding pending (GameEventListener#applyDetectedGame)
        InOrder order = inOrder(activations, gameEventListener, twitchService);
        order.verify(activations).clear(user);
        order.verify(gameEventListener).applyDetectedGame(user, game);
        order.verify(twitchService).resetToDefault(user);
    }

    @Test
    void deactivate_offline_withoutStreamEndDefault_leavesTheChannelAlone() {
        givenActive(binding("Art"));
        when(streamStateService.isLive(user)).thenReturn(false);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());
        UserSettings settings = new UserSettings();
        settings.setApplyDefaultOnStreamEnd(false);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        service.deactivate(user);

        verify(activations).clear(user);
        verify(twitchService, never()).resetToDefault(any());
        verify(twitchService, never()).updateChannel(any(), any());
        verify(gameEventListener, never()).applyDetectedGame(any(), any());
    }

    @Test
    void deactivate_nothingActive_doesNothing() {
        when(activations.active(user)).thenReturn(Optional.empty());

        service.deactivate(user);

        verify(activations, never()).clear(any());
        verifyNoInteractions(gameEventListener, twitchService, channelEventPublisher);
    }

    // ── Deletion ─────────────────────────────────────────────────────────────

    @Test
    void deleteActive_deactivatesFirst() {
        CustomBinding art = binding("Art");
        when(bindings.findByIdAndUser(art.getId(), user)).thenReturn(Optional.of(art));
        givenActive(art);
        when(streamStateService.isLive(user)).thenReturn(true);
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        service.delete(user, art.getId());

        InOrder order = inOrder(activations, gameEventListener, bindings, channelEventPublisher);
        order.verify(activations).clear(user);
        order.verify(gameEventListener).applyNoGameFallback(user);
        order.verify(bindings).delete(art);
        order.verify(channelEventPublisher).customBindingsUpdated(user.getId());
    }

    @Test
    void deleteInactive_leavesTheActiveOneAlone() {
        CustomBinding art = binding("Art");
        CustomBinding dev = binding("Dev");
        when(bindings.findByIdAndUser(art.getId(), user)).thenReturn(Optional.of(art));
        givenActive(dev);

        service.delete(user, art.getId());

        verify(bindings).delete(art);
        verify(activations, never()).clear(any());
        verifyNoInteractions(gameEventListener, twitchService);
        verify(channelEventPublisher).customBindingsUpdated(user.getId());
    }

    @Test
    void delete_unknown_isNotFound() {
        UUID id = UUID.randomUUID();
        when(bindings.findByIdAndUser(id, user)).thenReturn(Optional.empty());

        assertStatus(() -> service.delete(user, id), HttpStatus.NOT_FOUND);
    }

    // ── Gate ─────────────────────────────────────────────────────────────────

    @Test
    void gateClosed_everyOperationIsForbidden() {
        when(experimentService.evaluateGate(user, CustomBindingService.EXPERIMENT_KEY)).thenReturn(false);
        CustomBinding art = binding("Art");

        assertThat(service.enabled(user)).isFalse();
        assertStatus(() -> service.list(user), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.create(user, request("Art")), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.update(user, art.getId(), request("Art")), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.delete(user, art.getId()), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.resolve(user, "Art"), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.activate(user, art), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.deactivate(user), HttpStatus.FORBIDDEN);
        verifyNoInteractions(twitchService, gameEventListener);
        verify(bindings, never()).save(any());
        verify(bindings, never()).delete(any());
        verify(activations, never()).record(any(), any());
        verify(activations, never()).clear(any());
    }

    @Test
    void gateOpen_isEnabled() {
        assertThat(service.enabled(user)).isTrue();
    }

    @Test
    void startup_ensuresTheExperiment() {
        service.ensureExperiment();

        verify(experimentService).ensureExists("custom.bindings", "enabled");
    }
}
