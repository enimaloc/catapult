package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.domain.config.AppState;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.config.AppStateRepository;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TwBackfillServiceTest {

    /** A binding always has an owner (user_id is NOT NULL); the backfill runs each one as it. */
    private static UserAccount owner() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        return user;
    }

    private final GameMappingRepository mappings = mock(GameMappingRepository.class);

    @Test
    void skipsWhenFlagPresent() {
        AppStateRepository appRepo = mock(AppStateRepository.class);
        when(appRepo.findById("tw_backfill_completed"))
            .thenReturn(Optional.of(new AppState("tw_backfill_completed", "x", null)));
        TwBackfillService svc = build(appRepo, mock(GameBindingRepository.class),
            mock(IgdbService.class), mock(TwResolverService.class));
        svc.run();
        verify(appRepo, never()).save(any());
    }

    @Test
    void processesEligibleBindings_skipsOverride_persistsFlag() {
        AppStateRepository appRepo = mock(AppStateRepository.class);
        when(appRepo.findById("tw_backfill_completed")).thenReturn(Optional.empty());
        GameBindingRepository bRepo = mock(GameBindingRepository.class);

        GameBinding b1 = new GameBinding();

        b1.setUser(owner());
        b1.setId(UUID.randomUUID());
        b1.setSourceType(GameBinding.SourceType.STEAM);
        b1.setSourceId("42");
        b1.setSourceName("X");

        GameBinding b2 = new GameBinding();

        b2.setUser(owner());
        b2.setId(UUID.randomUUID());
        b2.setTwOverride(true);

        when(bRepo.findCandidatesForTwBackfill(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(b1, b2)));

        IgdbService igdb = mock(IgdbService.class);
        when(igdb.resolveIgdbIdForBinding(b1)).thenReturn(Optional.of("igdb42"));
        when(igdb.fetchDescriptorIds("igdb42")).thenReturn(Set.of(10L));
        TwResolverService res = mock(TwResolverService.class);
        when(res.suggest(any())).thenReturn(Set.of("violence_graphic"));

        TwBackfillService svc = build(appRepo, bRepo, igdb, res);
        svc.run();

        verify(bRepo, atLeastOnce()).save(b1);
        assertThat(b1.getTws()).containsExactly("violence_graphic");
        verify(appRepo).save(any(AppState.class));
    }

    @Test
    void forceRebuildAllIncludingPinned_doesNotSkipOverriddenBindings() {
        AppStateRepository appRepo = mock(AppStateRepository.class);
        GameBindingRepository bRepo = mock(GameBindingRepository.class);

        GameBinding pinned = new GameBinding();

        pinned.setUser(owner());
        pinned.setId(UUID.randomUUID());
        pinned.setSourceType(GameBinding.SourceType.STEAM);
        pinned.setSourceId("99");
        pinned.setSourceName("Pinned Game");
        pinned.setTwOverride(true);
        pinned.setTws(Set.of("old_tw"));

        when(bRepo.findAllCandidatesForTwForceRebuild(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(pinned)));

        IgdbService igdb = mock(IgdbService.class);
        when(igdb.resolveIgdbIdForBinding(pinned)).thenReturn(Optional.of("igdb99"));
        when(igdb.fetchDescriptorIds("igdb99")).thenReturn(Set.of(20L));
        TwResolverService res = mock(TwResolverService.class);
        when(res.suggest(any())).thenReturn(Set.of("new_tw"));

        TwBackfillService svc = build(appRepo, bRepo, igdb, res);
        svc.forceRebuildAllIncludingPinned();

        verify(bRepo).save(pinned);
        assertThat(pinned.getTws()).containsExactly("new_tw");
    }

    /** A MAPPED binding of a game whose admin mapping replaces the TWs (here by none), and one that doesn't. */
    private record MappedPair(GameBinding adminTws, GameBinding igdbTws, IgdbService igdb, TwResolverService res) {}

    private MappedPair mappedPair() {
        GameBinding adminTws = mappedBinding("730");
        GameBinding igdbTws = mappedBinding("440");
        GameMapping replacing = new GameMapping();
        replacing.setTwsOverridden(true);
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.of(replacing));
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "440"))
            .thenReturn(Optional.of(new GameMapping()));

        IgdbService igdb = mock(IgdbService.class);
        when(igdb.resolveIgdbIdForBinding(any())).thenReturn(Optional.of("igdb"));
        TwResolverService res = mock(TwResolverService.class);
        when(res.suggest(any())).thenReturn(Set.of("igdb_tw"));
        return new MappedPair(adminTws, igdbTws, igdb, res);
    }

    private static GameBinding mappedBinding(String sourceId) {
        GameBinding b = new GameBinding();
        b.setUser(owner());
        b.setId(UUID.randomUUID());
        b.setStatus(GameBinding.Status.MAPPED);
        b.setSourceType(GameBinding.SourceType.STEAM);
        b.setSourceId(sourceId);
        b.setSourceName("Game " + sourceId);
        return b;
    }

    @Test
    void backfill_skipsMappedBindingsWhoseMappingReplacesTheTws() {
        AppStateRepository appRepo = mock(AppStateRepository.class);
        when(appRepo.findById("tw_backfill_completed")).thenReturn(Optional.empty());
        GameBindingRepository bRepo = mock(GameBindingRepository.class);
        MappedPair p = mappedPair();
        when(bRepo.findCandidatesForTwBackfill(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(p.adminTws(), p.igdbTws())));

        build(appRepo, bRepo, p.igdb(), p.res()).run();

        // Replaced by none stays none; the mapping that keeps IGDB's TWs is backfilled as before.
        assertThat(p.adminTws().getTws()).isEmpty();
        verify(bRepo, never()).save(p.adminTws());
        assertThat(p.igdbTws().getTws()).containsExactly("igdb_tw");
    }

    @Test
    void rebuild_keepsTheAdminTwsOfMappedBindings() {
        GameBindingRepository bRepo = mock(GameBindingRepository.class);
        MappedPair p = mappedPair();
        p.adminTws().setTws(new java.util.HashSet<>(Set.of("admin_tw")));
        when(bRepo.findCandidatesForTwRebuild(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(p.adminTws(), p.igdbTws())));

        build(mock(AppStateRepository.class), bRepo, p.igdb(), p.res()).rebuildAllNonOverridden();

        assertThat(p.adminTws().getTws()).containsExactly("admin_tw");
        verify(bRepo, never()).save(p.adminTws());
        assertThat(p.igdbTws().getTws()).containsExactly("igdb_tw");
    }

    @Test
    void forceRebuild_keepsTheAdminTwsOfMappedBindings() {
        GameBindingRepository bRepo = mock(GameBindingRepository.class);
        MappedPair p = mappedPair();
        p.adminTws().setTws(new java.util.HashSet<>(Set.of("admin_tw")));
        when(bRepo.findAllCandidatesForTwForceRebuild(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(p.adminTws(), p.igdbTws())));

        build(mock(AppStateRepository.class), bRepo, p.igdb(), p.res()).forceRebuildAllIncludingPinned();

        assertThat(p.adminTws().getTws()).containsExactly("admin_tw");
        verify(bRepo, never()).save(p.adminTws());
        assertThat(p.igdbTws().getTws()).containsExactly("igdb_tw");
    }

    private TwBackfillService build(AppStateRepository app, GameBindingRepository b,
                                    IgdbService igdb, TwResolverService res) {
        TwBackfillService s = new TwBackfillService(app, b, mappings, igdb, res, new SimpleMeterRegistry());
        s.setBatchSize(50);
        s.setThrottle(1000);
        return s;
    }
}
