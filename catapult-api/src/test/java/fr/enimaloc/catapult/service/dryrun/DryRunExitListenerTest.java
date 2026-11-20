package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto.Type;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryRule;
import fr.enimaloc.catapult.service.category.CategoryTrigger.CustomBindingTriggered;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DryRunExitListenerTest {

    final TwitchService twitch = mock(TwitchService.class);
    final GameStateService gameStateService = mock(GameStateService.class);
    final ExtensionLayoutApplier layouts = new ExtensionLayoutApplier(twitch, mock(ActivityLogService.class));
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final CustomBindingActivations activations = mock(CustomBindingActivations.class);
    final CategoryChain categoryChain = mock(CategoryChain.class);
    final SiteRole siteRole = mock(SiteRole.class);
    final DryRunHeldDecisions held = new DryRunHeldDecisions();
    final DryRunExitListener listener = new DryRunExitListener(gameStateService, layouts, users, activations,
            categoryChain, siteRole, held);
    final UserAccount member = new UserAccount();

    @BeforeEach
    void setUp() {
        member.setId(UUID.randomUUID());
        when(users.findById(member.getId())).thenReturn(Optional.of(member));
        when(siteRole.isActive()).thenReturn(true);
        when(activations.active(member)).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(member)).thenReturn(Optional.empty());
        when(gameStateService.isObserved(member)).thenReturn(true);
    }

    private static CustomBindingActivation activation(UserAccount user) {
        CustomBinding binding = new CustomBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        return new CustomBindingActivation(user.getId(), binding, Instant.now());
    }

    @Test
    void anActiveCustomBindingIsAppliedAfterLeavingDryRun() {
        CustomBindingActivation active = activation(member);
        when(activations.active(member)).thenReturn(Optional.of(active));
        when(gameStateService.getLastKnownGame(member)).thenReturn(Optional.of(mock(DetectedGame.class)));

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verify(categoryChain).apply(member, new CustomBindingTriggered(active.getCustomBinding()));
        // the game open all along stays known: detected again, it would interrupt the binding
        verify(gameStateService, never()).forgetLastKnownGame(any());
    }

    @Test
    void exitResetsWhenTheHeldDecisionWasNoGame() {
        // the game closed during the dry run: the "no game" reset was held
        held.record(member.getId(), new GameClosed(), CategoryRule.NO_GAME_FALLBACK);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verify(categoryChain).apply(member, new GameClosed());
        assertThat(held.lastHeld(member.getId())).isEmpty();
    }

    @Test
    void exitDoesNotResetAHandSetCategory() {
        // no game open, but nothing closed during the dry run: the category is the streamer's own
        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verifyNoInteractions(categoryChain);
        verify(gameStateService).forgetLastKnownGame(member.getId());
    }

    @Test
    void exitDoesNotResetWhenTheHeldDecisionWasAGame() {
        held.record(member.getId(), new GameClosed(), CategoryRule.NO_GAME_FALLBACK);
        held.record(member.getId(), new GameClosed(), CategoryRule.GAME_BINDING);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verifyNoInteractions(categoryChain);
    }

    @Test
    void aManualChangeDuringDryRunClearsTheHeldDecision() {
        held.record(member.getId(), new GameClosed(), CategoryRule.NO_GAME_FALLBACK);
        // Catapult sends nothing in dry run: a category change seen on the channel is the streamer's
        held.onCategoryChanged(new ChannelCategoryChangedEvent(this, member, "509660", "Art"));

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verifyNoInteractions(categoryChain);
    }

    @Test
    void theHeldDecisionIsForgottenOnEveryInstance() {
        when(siteRole.isActive()).thenReturn(false);
        held.record(member.getId(), new GameClosed(), CategoryRule.NO_GAME_FALLBACK);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        assertThat(held.lastHeld(member.getId())).isEmpty();
    }

    @Test
    void aGameStillOpenIsLeftToTheNextDetection() {
        when(gameStateService.getLastKnownGame(member)).thenReturn(Optional.of(mock(DetectedGame.class)));

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verify(gameStateService).forgetLastKnownGame(member.getId());
        verifyNoInteractions(categoryChain);
    }

    @Test
    void anUnknownGameStateIsLeftToTheNextDetection() {
        // nothing observed since the restart: no game known does not mean no game open
        when(gameStateService.isObserved(member)).thenReturn(false);
        held.record(member.getId(), new GameClosed(), CategoryRule.NO_GAME_FALLBACK);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verifyNoInteractions(categoryChain);
    }

    @Test
    void exitReevaluatesOnlyOnTheActiveSite() {
        when(siteRole.isActive()).thenReturn(false);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verifyNoInteractions(categoryChain);
        verify(gameStateService).forgetLastKnownGame(member.getId());
    }

    @Test
    void aFailingReevaluationIsOnlyLogged() {
        when(categoryChain.apply(any(), any())).thenThrow(new IllegalStateException("boom"));
        held.record(member.getId(), new GameClosed(), CategoryRule.NO_GAME_FALLBACK);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));

        verify(categoryChain).apply(member, new GameClosed());
    }

    @Test
    void extensionsAreReappliedAfterLeavingDryRun() {
        // a game still open: left to the next detection, which puts its layout again
        when(gameStateService.getLastKnownGame(member)).thenReturn(Optional.of(mock(DetectedGame.class)));
        List<ExtensionSlotSetting> layout = List.of(new ExtensionSlotSetting(Type.PANEL, 1, "ext-1", "Ext One"));
        when(twitch.getUserExtensions(member)).thenReturn(List.of(
                new TwitchExtension(true, "ext-1", "Ext One", List.of(), "1.0")));
        when(twitch.getActiveExtensions(member)).thenReturn(TwitchExtensionPlacement.empty());
        // during the dry run: held by the gate, but taken as in place
        layouts.applyOnSwitch(member, "binding:1", layout);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));
        // the same game, detected again after the dry run
        layouts.applyOnSwitch(member, "binding:1", layout);

        verify(twitch, times(2)).setActiveExtensions(eq(member), any());
        verify(gameStateService).forgetLastKnownGame(member.getId());
    }
}
