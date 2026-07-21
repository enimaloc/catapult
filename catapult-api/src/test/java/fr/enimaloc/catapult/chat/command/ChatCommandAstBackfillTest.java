package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandAstBackfillTest {

    @Test
    void populatesAstOnlyForRowsMissingIt() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition needsBackfill = new ChatCommandDefinition();
        needsBackfill.setTemplate("Hi {game#name}");
        ChatCommandDefinition alreadyDone = new ChatCommandDefinition();
        alreadyDone.setTemplate("Hi {game#name}");
        alreadyDone.setAst("{\"nodes\":[]}");
        when(repository.findAll()).thenReturn(List.of(needsBackfill, alreadyDone));

        new ChatCommandAstBackfill(repository, new LegacyTemplateConverter()).run();

        assertThat(needsBackfill.getAst()).contains("\"type\":\"placeholder\"");
        verify(repository).saveAll(List.of(needsBackfill));
    }
}
