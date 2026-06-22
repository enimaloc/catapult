package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TwLabelServiceTest {
    @Test
    void resolvesFromMessageSourceWhenKeyExists() {
        MessageSource ms = mock(MessageSource.class);
        TwDefinitionRepository repo = mock(TwDefinitionRepository.class);
        when(ms.getMessage(eq("tw.violence_graphic.label"), any(), eq(Locale.FRENCH)))
            .thenReturn("Violence graphique");
        TwLabelService svc = new TwLabelService(ms, repo);
        assertThat(svc.resolve("violence_graphic", Locale.FRENCH)).isEqualTo("Violence graphique");
    }
    @Test
    void fallsBackToDbLabel() {
        MessageSource ms = mock(MessageSource.class);
        TwDefinitionRepository repo = mock(TwDefinitionRepository.class);
        when(ms.getMessage(eq("tw.x.label"), any(), any()))
            .thenThrow(new NoSuchMessageException("tw.x.label"));
        TwDefinition def = new TwDefinition();
        def.setId("x"); def.setLabel("X label");
        when(repo.findById("x")).thenReturn(Optional.of(def));
        TwLabelService svc = new TwLabelService(ms, repo);
        assertThat(svc.resolve("x", Locale.ENGLISH)).isEqualTo("X label");
    }
}
