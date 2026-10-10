package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** BindingService's per-binding edits: each saves, and pushes to Twitch only for the game being played. */
class BindingServiceEditsTest {

    private final GameBindingRepository bindings = mock(GameBindingRepository.class);
    private final IgdbService igdb = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbDetails = mock(IgdbGameDetailsService.class);
    private final TwitchService twitch = mock(TwitchService.class);
    private final TwResolverService twResolver = mock(TwResolverService.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final BindingService service = new BindingService(bindings, igdb, igdbDetails, twitch, twResolver, gameState);

    private UserAccount user;
    private GameBinding binding;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId("620");
        binding.setSourceName("Portal 2");
        when(bindings.findByIdAndUser(any(), any())).thenReturn(Optional.empty());
        when(bindings.findByIdAndUser(binding.getId(), user)).thenReturn(Optional.of(binding));
        when(bindings.save(any())).thenAnswer(call -> call.getArgument(0));
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());
    }

    private void playing(GameBinding.SourceType type, String sourceId) {
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.of(new DetectedGame(sourceId, type, "?")));
    }

    /** Runs {@code edit} twice: while another game is played (saved only), then while this one is (also pushed). */
    private void assertSavedAndPushedOnlyWhenActive(Consumer<BindingService> edit) {
        playing(GameBinding.SourceType.STEAM, "999");
        edit.accept(service);
        verify(bindings).save(binding);
        verify(twitch, never()).updateChannel(any(), any());

        playing(GameBinding.SourceType.STEAM, "620");
        edit.accept(service);
        verify(twitch).updateChannel(user, binding);
    }

    @Test
    void setTwitchGame_marksTheBindingManual() {
        assertSavedAndPushedOnlyWhenActive(s -> s.setTwitchGame(user, binding.getId(), "123", "Portal 2"));
        assertThat(binding.getTwitchGameId()).isEqualTo("123");
        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
    }

    @Test
    void cclAndTwToggles() {
        assertSavedAndPushedOnlyWhenActive(s -> s.toggleCclEnabled(user, binding.getId(), false));
        assertThat(binding.isCclEnabled()).isFalse();
    }

    @Test
    void twToggle() {
        assertSavedAndPushedOnlyWhenActive(s -> s.toggleTwEnabled(user, binding.getId(), false));
        assertThat(binding.isTwEnabled()).isFalse();
    }

    @Test
    void ignoring() {
        assertSavedAndPushedOnlyWhenActive(s -> s.toggleIgnored(user, binding.getId(), true));
        assertThat(binding.isIgnored()).isTrue();
    }

    @Test
    void settingTws_overridesTheSuggestion() {
        binding.getTws().add("old");

        assertSavedAndPushedOnlyWhenActive(s -> s.setTwsForBinding(user, binding.getId(), Set.of("gore")));

        assertThat(binding.getTws()).containsExactly("gore");
        assertThat(binding.isTwOverride()).isTrue();
    }

    @Test
    void fullUpdate_keepsTheStatusWithoutATwitchGame() {
        binding.setStatus(GameBinding.Status.AUTO);

        assertSavedAndPushedOnlyWhenActive(s -> s.updateBinding(user, binding.getId(), " ", null, Set.of("Gambling"), Set.of("gore"), false));

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.AUTO);
        assertThat(binding.getCcls()).containsExactly("Gambling");
        assertThat(binding.isTwOverride()).isTrue();
    }

    @Test
    void activeGamesOfAnotherSource_areNotThisBinding() {
        playing(GameBinding.SourceType.XBOX, "620");

        service.toggleIgnored(user, binding.getId(), true);

        verify(twitch, never()).updateChannel(any(), any());
    }

    @Test
    void editsOfUnknownBindings_doNothing() {
        UUID unknown = UUID.randomUUID();

        service.setTwitchGame(user, unknown, "1", "x");
        service.toggleCclEnabled(user, unknown, true);
        service.toggleTwEnabled(user, unknown, true);
        service.toggleIgnored(user, unknown, true);
        service.setTwsForBinding(user, unknown, Set.of());
        service.resetTws(user, unknown);
        service.deleteBinding(user, unknown);

        verify(bindings, never()).save(any());
        verify(bindings, never()).delete(any());
        verifyNoInteractions(twitch);
    }

    @Test
    void resetTws_reResolvesTheSuggestion_withoutPushingToTwitch() {
        binding.setTwOverride(true);
        playing(GameBinding.SourceType.STEAM, "620");
        when(igdb.findByExternalAppId(GameBinding.SourceType.STEAM, "620"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("72", "Portal 2")));
        when(igdbDetails.getDetails("72")).thenReturn(Optional.empty());
        when(igdb.findTwitchGameId("72")).thenReturn(Optional.of("tw-72"));
        when(igdb.suggestCcls("72")).thenReturn(Set.of());
        when(igdb.fetchDescriptorIds("72")).thenReturn(Set.of(29L));
        when(twResolver.suggest(any())).thenReturn(Set.of("gore"));

        service.resetTws(user, binding.getId());

        assertThat(binding.isTwOverride()).isFalse();
        assertThat(binding.getTws()).containsExactly("gore");
        ArgumentCaptor<TwResolverService.SuggestInput> input = ArgumentCaptor.forClass(TwResolverService.SuggestInput.class);
        verify(twResolver).suggest(input.capture());
        assertThat(input.getValue().steamAppId()).isEqualTo("620");
        verify(bindings).save(binding);
        verify(twitch, never()).updateChannel(any(), any());
    }

    @Test
    void previewTws_usesTheResolvedIgdbGame_andSteamSignalsOnlyForSteam() {
        binding.setSourceType(GameBinding.SourceType.XBOX);
        when(igdb.resolveIgdbIdForBinding(binding)).thenReturn(Optional.of("72"));
        when(igdb.fetchDescriptorIds("72")).thenReturn(Set.of());
        when(twResolver.suggest(any())).thenReturn(Set.of("gore"));

        assertThat(service.previewTws(user, binding.getId())).containsExactly("gore");
        ArgumentCaptor<TwResolverService.SuggestInput> input = ArgumentCaptor.forClass(TwResolverService.SuggestInput.class);
        verify(twResolver).suggest(input.capture());
        assertThat(input.getValue().steamAppId()).isNull();

        when(igdb.resolveIgdbIdForBinding(binding)).thenReturn(Optional.empty());
        assertThat(service.previewTws(user, binding.getId())).isEmpty();
        assertThat(service.previewTws(user, UUID.randomUUID())).isEmpty();
    }

    @Test
    void deleteAndFind() {
        service.deleteBinding(user, binding.getId());
        verify(bindings).delete(binding);

        assertThat(service.findBinding(user, binding.getId())).contains(binding);
        when(bindings.findByUserAndSourceIdAndSourceType(user, "620", GameBinding.SourceType.STEAM)).thenReturn(Optional.of(binding));
        assertThat(service.findBinding(user, new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2"))).contains(binding);
    }

    @Test
    void refreshingIncompleteBindings_countsTheResolvedOnes() {
        when(bindings.findAllByStatusAndIgnoredFalse(GameBinding.Status.INCOMPLETE)).thenReturn(List.of(binding));
        when(igdb.findByExternalAppId(any(), anyString())).thenReturn(Optional.empty());
        when(igdb.findByName("Portal 2")).thenReturn(Optional.empty());

        service.refreshIncompleteBindings();

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.INCOMPLETE);
        verify(bindings).save(binding);

        when(bindings.findAllByStatusAndIgnoredFalse(GameBinding.Status.INCOMPLETE)).thenReturn(List.of());
        service.refreshIncompleteBindings();
    }
}
