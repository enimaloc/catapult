package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.event.TwDefinitionsChangedEvent;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TwPlaceholderRegistryTest {
    @Test
    void loadsKnownSlugsOnInitAndRefresh() {
        TwDefinitionRepository repo = mock(TwDefinitionRepository.class);
        TwDefinition d = new TwDefinition(); d.setId("violence_graphic"); d.setLabel("X");
        when(repo.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(d));

        TwPlaceholderRegistry reg = new TwPlaceholderRegistry(repo);
        reg.load();
        assertThat(reg.getKnownPaths()).containsExactly("violence_graphic");

        when(repo.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        reg.onChanged(new TwDefinitionsChangedEvent(this));
        assertThat(reg.getKnownPaths()).isEmpty();
    }
}
