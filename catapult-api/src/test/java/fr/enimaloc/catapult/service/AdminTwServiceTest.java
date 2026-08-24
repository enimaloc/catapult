package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.domain.TwSteamContentIdMapping;
import fr.enimaloc.catapult.domain.TwSteamKeyword;
import fr.enimaloc.catapult.event.TwDefinitionsChangedEvent;
import fr.enimaloc.catapult.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminTwServiceTest {

    @Test
    void create_validatesSlugAndLabel_persists_publishesEvent() {
        TwDefinitionRepository defRepo = mock(TwDefinitionRepository.class);
        var pub = mock(ApplicationEventPublisher.class);
        AdminTwService svc = newService(defRepo, pub);
        when(defRepo.existsById("ok_slug")).thenReturn(false);
        when(defRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        TwDefinition created = svc.create("ok_slug", "OK", "desc", 3);
        assertThat(created.getId()).isEqualTo("ok_slug");
        verify(defRepo).save(any());
        verify(pub).publishEvent(any(TwDefinitionsChangedEvent.class));
    }

    @Test
    void create_rejectsBadSlug() {
        AdminTwService svc = newService(mock(TwDefinitionRepository.class), mock(ApplicationEventPublisher.class));
        assertThatThrownBy(() -> svc.create("Bad Slug!", "label", null, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_refusesWhenStillReferenced() {
        TwDefinitionRepository defRepo = mock(TwDefinitionRepository.class);
        GameBindingRepository bindingRepo = mock(GameBindingRepository.class);
        when(bindingRepo.existsByTwsContaining("violence_graphic")).thenReturn(true);
        AdminTwService svc = newService(defRepo, bindingRepo, mock(ApplicationEventPublisher.class));
        assertThatThrownBy(() -> svc.delete("violence_graphic"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addSteamKeyword_normalizesAndPersists_publishesEvent() {
        TwDefinitionRepository defRepo = mock(TwDefinitionRepository.class);
        TwSteamKeywordRepository kwRepo = mock(TwSteamKeywordRepository.class);
        var pub = mock(ApplicationEventPublisher.class);
        when(defRepo.existsById("gore")).thenReturn(true);
        when(kwRepo.findAllByTwId("gore")).thenReturn(List.of());
        AdminTwService svc = new AdminTwService(
            defRepo, mock(TwDtddTopicMappingRepository.class), mock(TwIgdbDescriptorMappingRepository.class),
            mock(TwSteamContentIdMappingRepository.class), kwRepo,
            mock(GameBindingRepository.class), pub, mock(SteamStoreService.class));

        svc.addSteamKeyword("gore", "  BLOOD  ");

        verify(kwRepo).save(argThat(k -> k.getKeyword().equals("blood") && k.getTwId().equals("gore")));
        verify(pub).publishEvent(any(TwDefinitionsChangedEvent.class));
    }

    @Test
    void addSteamKeyword_rejectsTooShortKeyword() {
        TwDefinitionRepository defRepo = mock(TwDefinitionRepository.class);
        when(defRepo.existsById("gore")).thenReturn(true);
        AdminTwService svc = new AdminTwService(
            defRepo, mock(TwDtddTopicMappingRepository.class), mock(TwIgdbDescriptorMappingRepository.class),
            mock(TwSteamContentIdMappingRepository.class), mock(TwSteamKeywordRepository.class),
            mock(GameBindingRepository.class), mock(ApplicationEventPublisher.class), mock(SteamStoreService.class));

        assertThatThrownBy(() -> svc.addSteamKeyword("gore", "a"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeSteamKeyword_deletesMatchingEntry() {
        TwDefinitionRepository defRepo = mock(TwDefinitionRepository.class);
        TwSteamKeywordRepository kwRepo = mock(TwSteamKeywordRepository.class);
        when(defRepo.existsById("gore")).thenReturn(true);
        TwSteamKeyword existing = new TwSteamKeyword();
        existing.setTwId("gore");
        existing.setKeyword("blood");
        when(kwRepo.findAllByTwId("gore")).thenReturn(List.of(existing));
        var pub = mock(ApplicationEventPublisher.class);
        AdminTwService svc = new AdminTwService(
            defRepo, mock(TwDtddTopicMappingRepository.class), mock(TwIgdbDescriptorMappingRepository.class),
            mock(TwSteamContentIdMappingRepository.class), kwRepo,
            mock(GameBindingRepository.class), pub, mock(SteamStoreService.class));

        svc.removeSteamKeyword("gore", "BLOOD");

        verify(kwRepo).delete(existing);
        verify(pub).publishEvent(any(TwDefinitionsChangedEvent.class));
    }

    @Test
    void testSteamSignals_reportsMatchedKeywordsDraftAndContentIds() {
        TwDefinitionRepository defRepo = mock(TwDefinitionRepository.class);
        TwSteamKeywordRepository kwRepo = mock(TwSteamKeywordRepository.class);
        TwSteamContentIdMappingRepository idRepo = mock(TwSteamContentIdMappingRepository.class);
        SteamStoreService steamService = mock(SteamStoreService.class);
        when(defRepo.existsById("gore")).thenReturn(true);

        TwSteamKeyword blood = new TwSteamKeyword();
        blood.setTwId("gore");
        blood.setKeyword("blood");
        TwSteamKeyword gore = new TwSteamKeyword();
        gore.setTwId("gore");
        gore.setKeyword("dismember");
        when(kwRepo.findAllByTwId("gore")).thenReturn(List.of(blood, gore));

        TwSteamContentIdMapping mapping = new TwSteamContentIdMapping();
        mapping.setTwId("gore");
        mapping.setSteamContentId(2);
        when(idRepo.findAllByTwId("gore")).thenReturn(List.of(mapping));

        when(steamService.fetchTwSignals(List.of("730"))).thenReturn(Map.of(
            "730", new SteamStoreService.SteamTwSignals(Set.of(2, 5), "contains blood and gore imagery")));

        AdminTwService svc = new AdminTwService(
            defRepo, mock(TwDtddTopicMappingRepository.class), mock(TwIgdbDescriptorMappingRepository.class),
            idRepo, kwRepo, mock(GameBindingRepository.class), mock(ApplicationEventPublisher.class), steamService);

        AdminTwService.SteamSignalTestResult result = svc.testSteamSignals("gore", "730", "gore");

        assertThat(result.matchedKeywords()).containsExactly("blood");
        assertThat(result.draftKeywordMatch()).isTrue();
        assertThat(result.matchedContentDescriptorIds()).containsExactly(2);
    }

    private AdminTwService newService(TwDefinitionRepository defRepo, ApplicationEventPublisher pub) {
        return newService(defRepo, mock(GameBindingRepository.class), pub);
    }

    private AdminTwService newService(TwDefinitionRepository defRepo, GameBindingRepository bindingRepo, ApplicationEventPublisher pub) {
        return new AdminTwService(
            defRepo,
            mock(TwDtddTopicMappingRepository.class),
            mock(TwIgdbDescriptorMappingRepository.class),
            mock(TwSteamContentIdMappingRepository.class),
            mock(TwSteamKeywordRepository.class),
            bindingRepo, pub,
            mock(SteamStoreService.class));
    }
}
