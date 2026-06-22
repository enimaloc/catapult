package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.event.TwDefinitionsChangedEvent;
import fr.enimaloc.catapult.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

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
            bindingRepo, pub);
    }
}
