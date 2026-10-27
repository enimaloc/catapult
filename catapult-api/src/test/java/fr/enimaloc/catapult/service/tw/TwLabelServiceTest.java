package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.tw.TwDefinition;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TwLabelServiceTest {
    @Test
    void resolvesFromMessageSourceWhenKeyExists() {
        MessageSource ms = mock(MessageSource.class);
        TwDefinitionRepository repo = mock(TwDefinitionRepository.class);
        when(ms.getMessage(eq("tw.violence_graphic.label"), any(), any(), eq(Locale.FRENCH)))
            .thenReturn("Violence graphique");
        TwLabelService svc = new TwLabelService(ms, repo);
        assertThat(svc.resolve("violence_graphic", Locale.FRENCH)).isEqualTo("Violence graphique");
    }
    @Test
    void fallsBackToDbLabel() {
        MessageSource ms = mock(MessageSource.class);
        TwDefinitionRepository repo = mock(TwDefinitionRepository.class);
        when(ms.getMessage(eq("tw.x.label"), any(), any(), any(Locale.class)))
            .thenReturn(null);
        TwDefinition def = new TwDefinition();
        def.setId("x"); def.setLabel("X label");
        when(repo.findById("x")).thenReturn(Optional.of(def));
        TwLabelService svc = new TwLabelService(ms, repo);
        assertThat(svc.resolve("x", Locale.ENGLISH)).isEqualTo("X label");
    }
    @Test
    void missingBundleKeyFallsBackToTheDbLabelThenTheSlug() {
        // IcuMessageSource echoes the code back on a miss instead of throwing
        MessageSource ms = mock(MessageSource.class);
        when(ms.getMessage(anyString(), any(), any(), any(Locale.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        TwDefinitionRepository repo = mock(TwDefinitionRepository.class);
        TwDefinition def = new TwDefinition();
        def.setId("x"); def.setLabel("X label");
        when(repo.findById("x")).thenReturn(Optional.of(def));
        when(repo.findById("y")).thenReturn(Optional.empty());
        TwLabelService svc = new TwLabelService(ms, repo);
        assertThat(svc.resolve("x", Locale.ENGLISH)).isEqualTo("X label");
        assertThat(svc.resolve("y", Locale.ENGLISH)).isEqualTo("y");
    }
}
