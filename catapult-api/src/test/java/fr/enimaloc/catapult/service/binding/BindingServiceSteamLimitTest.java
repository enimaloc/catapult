package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.event.TwsMissedSteamEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.category.CategoryApplier;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A Steam game's TWs resolved while the Steam store is rate limited: told, then resolved again once it may be called. */
class BindingServiceSteamLimitTest {

    private static final Instant UNTIL = Instant.parse("2026-11-12T10:05:00Z");

    private final GameBindingRepository bindings = mock(GameBindingRepository.class);
    private final GameMappingRepository mappings = mock(GameMappingRepository.class);
    private final IgdbService igdb = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbDetails = mock(IgdbGameDetailsService.class);
    private final TwitchService twitch = mock(TwitchService.class);
    private final TwResolverService twResolver = mock(TwResolverService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BindingService service = new BindingService(bindings, igdb, igdbDetails, twitch, twResolver, mappings,
            new GameBindingLookup(bindings), new CategoryChain(mock(CustomBindingActivations.class),
            mock(StreamStateService.class), mock(UserSettingsRepository.class), new RaidRestoreHold(),
            mock(GameStateService.class), mock(UserAccountRepository.class),
            new CategoryApplier(twitch, mock(StreamStateService.class)), mock(RaidRestoreGateService.class),
            mock(GameBindingLookup.class), mock(ExtensionLayoutApplier.class)), mock(ExtensionLayoutApplier.class), events);

    private final UserAccount user = new UserAccount();
    private final DetectedGame steamGame = new DetectedGame("730", GameBinding.SourceType.STEAM, "Counter-Strike 2");

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        when(bindings.save(any())).thenAnswer(call -> {
            GameBinding b = call.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return b;
        });
        when(mappings.findBySourceTypeAndSourceId(any(), any())).thenReturn(Optional.empty());
        when(igdb.resolveDetected(eq(GameBinding.SourceType.STEAM), eq("730"), any()))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("igdb-1", "Counter-Strike 2")));
        when(igdbDetails.getDetails(anyString())).thenReturn(Optional.empty());
        when(igdb.findTwitchGameId("igdb-1")).thenReturn(Optional.of("32399"));
        when(igdb.resolveIgdbIdForBinding(any())).thenReturn(Optional.of("igdb-1"));
        when(twResolver.suggest(any())).thenReturn(Set.of("violence"));
        when(twResolver.steamRateLimitedUntil()).thenReturn(Optional.of(UNTIL));
    }

    private GameBinding binding(GameBinding.Status status) {
        GameBinding b = new GameBinding();
        b.setId(UUID.randomUUID());
        b.setUser(user);
        b.setSourceType(GameBinding.SourceType.STEAM);
        b.setSourceId("730");
        b.setSourceName("Counter-Strike 2");
        b.setStatus(status);
        b.getTws().add("old");
        when(bindings.findById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void aSteamGameResolvedWhileLimited_isTold() {
        when(bindings.findByUserAndSourceIdAndSourceType(user, "730", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.empty());

        GameBinding created = service.resolveOrCreate(user, steamGame);

        verify(events).publishEvent(new TwsMissedSteamEvent(created.getId(), UNTIL));
    }

    @Test
    void notWhenTheStoreMayBeCalled_norForAnotherPlatform() {
        when(twResolver.steamRateLimitedUntil()).thenReturn(Optional.empty());
        when(bindings.findByUserAndSourceIdAndSourceType(any(), any(), any())).thenReturn(Optional.empty());
        service.resolveOrCreate(user, steamGame);

        when(twResolver.steamRateLimitedUntil()).thenReturn(Optional.of(UNTIL));
        when(igdb.resolveDetected(eq(GameBinding.SourceType.XBOX), eq("x1"), any()))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("igdb-2", "Halo")));
        service.resolveOrCreate(user, new DetectedGame("x1", GameBinding.SourceType.XBOX, "Halo"));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void notWhenAnAdminMappingSetsTheTws() {
        GameMapping mapping = new GameMapping();
        mapping.setTwitchGameId("32399");
        mapping.setTwitchGameName("CS");
        mapping.setTwsOverridden(true);
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.of(mapping));
        when(bindings.findByUserAndSourceIdAndSourceType(any(), any(), any())).thenReturn(Optional.empty());

        service.resolveOrCreate(user, steamGame);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void theRetry_savesTheTwsOnceSteamAnswers() {
        GameBinding binding = binding(GameBinding.Status.AUTO);
        when(twResolver.suggestReporting(any())).thenReturn(
                new TwResolverService.Suggestion(Set.of("violence", "gore"), Optional.empty()));

        Optional<GameBinding> retried = service.retryTwsAfterSteamLimit(binding.getId());

        assertThat(retried).contains(binding);
        assertThat(binding.getTws()).containsExactlyInAnyOrder("violence", "gore");
        verify(bindings).save(binding);
    }

    @Test
    void theRetry_stillLimited_isToldAgain_andChangesNothing() {
        GameBinding binding = binding(GameBinding.Status.AUTO);
        Instant later = UNTIL.plusSeconds(120);
        when(twResolver.suggestReporting(any())).thenReturn(
                new TwResolverService.Suggestion(Set.of("violence"), Optional.of(later)));

        assertThat(service.retryTwsAfterSteamLimit(binding.getId())).isEmpty();
        assertThat(binding.getTws()).containsExactly("old");
        verify(events).publishEvent(new TwsMissedSteamEvent(binding.getId(), later));
        verify(bindings, never()).save(any());
    }

    @Test
    void theRetry_leavesTheStreamersAndAnAdminsChoiceAlone() {
        GameBinding pinned = binding(GameBinding.Status.AUTO);
        pinned.setTwOverride(true);
        GameBinding manual = binding(GameBinding.Status.MANUAL);
        GameBinding mapped = binding(GameBinding.Status.MAPPED);
        GameMapping mapping = new GameMapping();
        mapping.setTwsOverridden(true);
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.of(mapping));

        assertThat(service.retryTwsAfterSteamLimit(pinned.getId())).isEmpty();
        assertThat(service.retryTwsAfterSteamLimit(manual.getId())).isEmpty();
        assertThat(service.retryTwsAfterSteamLimit(mapped.getId())).isEmpty();
        verify(twResolver, never()).suggestReporting(any());
    }

    @Test
    void theRetry_ofUnchangedTws_pushesNothing() {
        GameBinding binding = binding(GameBinding.Status.AUTO);
        when(twResolver.suggestReporting(any())).thenReturn(new TwResolverService.Suggestion(Set.of("old"), Optional.empty()));

        assertThat(service.retryTwsAfterSteamLimit(binding.getId())).isEmpty();
        verify(bindings, never()).save(any());
    }
}
