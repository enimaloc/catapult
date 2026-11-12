package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.tw.TwSteamKeyword;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwIgdbDescriptorMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamContentIdMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamKeywordRepository;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.steam.SteamStoreService.SteamTwSignals;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TwResolverServiceTest {

    @Test
    void aggregatesAllSources_dtddIgdbSteamIdsAndKeywords() {
        TwIgdbDescriptorMappingRepository igdbRepo = mock(TwIgdbDescriptorMappingRepository.class);
        TwSteamContentIdMappingRepository steamIdRepo = mock(TwSteamContentIdMappingRepository.class);
        TwSteamKeywordRepository steamKwRepo = mock(TwSteamKeywordRepository.class);
        TwDtddTopicMappingRepository dtddRepo = mock(TwDtddTopicMappingRepository.class);
        DtddSignalService dtdd = mock(DtddSignalService.class);
        SteamStoreService steam = mock(SteamStoreService.class);

        when(igdbRepo.findTwIdsByDescriptorIds(Set.of(10L,11L)))
            .thenReturn(Set.of("violence_graphic"));
        when(steam.fetchTwSignals(List.of("app42"))).thenReturn(Map.of(
            "app42", new SteamTwSignals(Set.of(2, 5), "blood and gore present")));
        when(steamIdRepo.findTwIdsBySteamContentIdIn(Set.of(2,5)))
            .thenReturn(Set.of("violence_graphic"));
        TwSteamKeyword kw1 = new TwSteamKeyword(); kw1.setKeyword("gore"); kw1.setTwId("violence_graphic");
        TwSteamKeyword kw2 = new TwSteamKeyword(); kw2.setKeyword("flashing"); kw2.setTwId("flashing_lights");
        when(steamKwRepo.findAll()).thenReturn(List.of(kw1, kw2));
        when(dtdd.getYesMostlyTopics("igdb42", "Stardew"))
            .thenReturn(Set.of("A dog dies"));
        when(dtddRepo.findTwIdsByDtddTopicNameInIgnoreCase(Set.of("a dog dies")))
            .thenReturn(Set.of("death_of_animal"));

        TwResolverService svc = new TwResolverService(
            igdbRepo, steamIdRepo, steamKwRepo, dtddRepo, Optional.of(dtdd), steam);

        var out = svc.suggest(new TwResolverService.SuggestInput(
            "igdb42", Set.of(10L,11L), "app42", "Stardew"));
        assertThat(out).containsExactlyInAnyOrder("violence_graphic", "death_of_animal");
        // Note: "flashing" not in notes → flashing_lights not selected.
    }

    @Test
    void dtddAbsent_skipsSilently() {
        TwResolverService svc = new TwResolverService(
            mock(TwIgdbDescriptorMappingRepository.class),
            mock(TwSteamContentIdMappingRepository.class),
            mock(TwSteamKeywordRepository.class),
            mock(TwDtddTopicMappingRepository.class),
            Optional.empty(),
            mock(SteamStoreService.class));
        assertThat(svc.suggest(new TwResolverService.SuggestInput(
            "x", Set.of(), null, "x"))).isEmpty();
    }

    @Test
    void steamSignalsMissedWhileRateLimited_areReported_andOtherwiseNot() {
        SteamStoreService steam = mock(SteamStoreService.class);
        TwResolverService svc = new TwResolverService(
            mock(TwIgdbDescriptorMappingRepository.class), mock(TwSteamContentIdMappingRepository.class),
            mock(TwSteamKeywordRepository.class), mock(TwDtddTopicMappingRepository.class), Optional.empty(), steam);
        java.time.Instant until = java.time.Instant.parse("2026-11-12T10:05:00Z");
        when(steam.fetchTwSignals(List.of("app42"))).thenReturn(Map.of());
        when(steam.rateLimitedUntil()).thenReturn(Optional.of(until));

        assertThat(svc.suggestReporting(new TwResolverService.SuggestInput("x", Set.of(), "app42", "x"))
            .steamLimitedUntil()).contains(until);
        // Not a Steam game: nothing asked of Steam, nothing missed
        assertThat(svc.suggestReporting(new TwResolverService.SuggestInput("x", Set.of(), null, "x"))
            .steamLimitedUntil()).isEmpty();
        // Steam answered
        when(steam.fetchTwSignals(List.of("app42"))).thenReturn(Map.of("app42", SteamTwSignals.empty()));
        assertThat(svc.suggestReporting(new TwResolverService.SuggestInput("x", Set.of(), "app42", "x"))
            .steamLimitedUntil()).isEmpty();
    }
}
