package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.AppState;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.repository.AppStateRepository;
import fr.enimaloc.catapult.repository.GameBindingRepository;
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
        b1.setId(UUID.randomUUID());
        b1.setSourceType(GameBinding.SourceType.STEAM);
        b1.setSourceId("42");
        b1.setSourceName("X");

        GameBinding b2 = new GameBinding();
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

    private TwBackfillService build(AppStateRepository app, GameBindingRepository b,
                                    IgdbService igdb, TwResolverService res) {
        TwBackfillService s = new TwBackfillService(app, b, igdb, res, new SimpleMeterRegistry());
        s.setBatchSize(50);
        s.setThrottle(1000);
        return s;
    }
}
