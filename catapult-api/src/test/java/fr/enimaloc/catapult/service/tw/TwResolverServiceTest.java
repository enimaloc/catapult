package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.tw.TwDtddTopicMapping;
import fr.enimaloc.catapult.domain.tw.TwIgdbDescriptorMapping;
import fr.enimaloc.catapult.domain.tw.TwSteamContentIdMapping;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddSeverity;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService.TopicSignals;
import static org.assertj.core.api.Assertions.tuple;

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

    private final TwIgdbDescriptorMappingRepository igdbRepo = mock(TwIgdbDescriptorMappingRepository.class);
    private final TwSteamContentIdMappingRepository steamIdRepo = mock(TwSteamContentIdMappingRepository.class);
    private final TwSteamKeywordRepository steamKwRepo = mock(TwSteamKeywordRepository.class);
    private final TwDtddTopicMappingRepository dtddRepo = mock(TwDtddTopicMappingRepository.class);
    private final DtddSignalService dtdd = mock(DtddSignalService.class);
    private final SteamStoreService steam = mock(SteamStoreService.class);
    private final TwResolverService svc =
            new TwResolverService(igdbRepo, steamIdRepo, steamKwRepo, dtddRepo, Optional.of(dtdd), steam);

    private static TwDtddTopicMapping dtddMapping(String twId, String topic, String minSeverity) {
        TwDtddTopicMapping m = new TwDtddTopicMapping();
        m.setTwId(twId);
        m.setDtddTopicName(topic);
        m.setMinSeverity(minSeverity);
        return m;
    }

    private static DtddSeverity gore(int minor, int depicted, int excessive) {
        return new DtddSeverity(List.of("Minor", "Depicted", "Excessive"), List.of(minor, depicted, excessive),
                List.of(), minor + depicted + excessive, null);
    }

    @Test
    void aggregatesAllSources_dtddIgdbSteamIdsAndKeywords() {
        TwIgdbDescriptorMapping igdb = new TwIgdbDescriptorMapping();
        igdb.setTwId("violence_graphic");
        igdb.setDescriptorId(10L);
        when(igdbRepo.findAllByDescriptorIdIn(Set.of(10L, 11L))).thenReturn(List.of(igdb));
        when(steam.fetchTwSignals(List.of("app42"))).thenReturn(Map.of(
            "app42", new SteamTwSignals(Set.of(2, 5), "blood and gore present")));
        TwSteamContentIdMapping steamId = new TwSteamContentIdMapping();
        steamId.setTwId("violence_graphic");
        steamId.setSteamContentId(2);
        when(steamIdRepo.findAllBySteamContentIdIn(Set.of(2, 5))).thenReturn(List.of(steamId));
        TwSteamKeyword kw1 = new TwSteamKeyword(); kw1.setKeyword("gore"); kw1.setTwId("violence_graphic");
        TwSteamKeyword kw2 = new TwSteamKeyword(); kw2.setKeyword("flashing"); kw2.setTwId("flashing_lights");
        when(steamKwRepo.findAll()).thenReturn(List.of(kw1, kw2));
        when(dtdd.getYesMostlySignals("igdb42", "Stardew"))
            .thenReturn(new TopicSignals(Set.of("A dog dies"), Map.of()));
        when(dtddRepo.findAllByDtddTopicNameInIgnoreCase(Set.of("a dog dies")))
            .thenReturn(List.of(dtddMapping("death_of_animal", "a dog dies", null)));

        var in = new TwResolverService.SuggestInput("igdb42", Set.of(10L, 11L), "app42", "Stardew");
        assertThat(svc.suggest(in)).containsExactlyInAnyOrder("violence_graphic", "death_of_animal");
        // Note: "flashing" not in notes → flashing_lights not selected.

        // Each source told, the DTDD topic under its own name
        assertThat(svc.explain(in).signals()).extracting(TwResolverService.Signal::source, TwResolverService.Signal::value)
            .containsExactlyInAnyOrder(
                tuple(TwResolverService.SourceType.IGDB_DESCRIPTOR, "10"),
                tuple(TwResolverService.SourceType.STEAM_CONTENT, "2"),
                tuple(TwResolverService.SourceType.STEAM_KEYWORD, "gore"),
                tuple(TwResolverService.SourceType.DTDD_TOPIC, "A dog dies"));
    }

    @Test
    void aDtddTopicBelowItsThreshold_keepsItsTwOff_andIsTold() {
        when(dtdd.getYesMostlySignals("igdb42", "Game")).thenReturn(new TopicSignals(
            Set.of("there's blood/gore", "a dog dies"), Map.of("there's blood/gore", gore(8, 1, 1))));
        when(dtddRepo.findAllByDtddTopicNameInIgnoreCase(Set.of("there's blood/gore", "a dog dies"))).thenReturn(List.of(
            dtddMapping("gore", "there's blood/gore", "Depicted"),
            dtddMapping("violence", "there's blood/gore", null),
            dtddMapping("death_of_animal", "a dog dies", "Depicted")));

        var explanation = svc.explain(new TwResolverService.SuggestInput("igdb42", Set.of(), null, "Game"));

        // gore kept off (80 % Minor); the same topic without a threshold still turns violence on; a topic without
        // levels still turns its TW on
        assertThat(explanation.twIds()).containsExactlyInAnyOrder("violence", "death_of_animal");
        assertThat(explanation.dropped()).singleElement().satisfies(s -> {
            assertThat(s.twId()).isEqualTo("gore");
            assertThat(s.severity().reason()).isEqualTo(TwSeverityRule.Reason.BELOW_VOTES);
            assertThat(s.severity().percentAtOrAbove()).isEqualTo(20);
        });
    }

    @Test
    void severitiesNotCachedYet_turnTheTwOnAsBefore() {
        when(dtdd.getYesMostlySignals("igdb42", "Game")).thenReturn(new TopicSignals(Set.of("there's blood/gore"), null));
        when(dtddRepo.findAllByDtddTopicNameInIgnoreCase(Set.of("there's blood/gore")))
            .thenReturn(List.of(dtddMapping("gore", "there's blood/gore", "Depicted")));

        assertThat(svc.suggest(new TwResolverService.SuggestInput("igdb42", Set.of(), null, "Game")))
            .containsExactly("gore");
    }

    @Test
    void theVotesNeeded_areConfigurable() {
        when(dtdd.getYesMostlySignals("igdb42", "Game")).thenReturn(new TopicSignals(
            Set.of("there's blood/gore"), Map.of("there's blood/gore", gore(4, 0, 0))));
        when(dtddRepo.findAllByDtddTopicNameInIgnoreCase(Set.of("there's blood/gore")))
            .thenReturn(List.of(dtddMapping("gore", "there's blood/gore", "Depicted")));
        var in = new TwResolverService.SuggestInput("igdb42", Set.of(), null, "Game");

        assertThat(svc.suggest(in)).isEmpty();
        svc.setDtddSeverityMinVotes(5);
        assertThat(svc.suggest(in)).containsExactly("gore");
    }

    @Test
    void dtddAbsent_skipsSilently() {
        TwResolverService none = new TwResolverService(igdbRepo, steamIdRepo, steamKwRepo, dtddRepo, Optional.empty(),
            steam);
        assertThat(none.suggest(new TwResolverService.SuggestInput("x", Set.of(), null, "x"))).isEmpty();
    }
}
