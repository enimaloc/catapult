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
        alreadyDone.setAst("{\"statements\":[]}");
        when(repository.findAll()).thenReturn(List.of(needsBackfill, alreadyDone));

        new ChatCommandAstBackfill(repository, new LegacyTemplateConverter()).run();

        assertThat(needsBackfill.getAst()).contains("\"type\":\"context-get\"");
        verify(repository).saveAll(List.of(needsBackfill));
    }

    @Test
    void skipsMalformedTemplateWithoutFailingTheWholeBatch() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition malformed = new ChatCommandDefinition();
        malformed.setName("broken");
        malformed.setTemplate("Hi {game#name");
        ChatCommandDefinition valid = new ChatCommandDefinition();
        valid.setName("ok");
        valid.setTemplate("Hi {game#name}");
        when(repository.findAll()).thenReturn(List.of(malformed, valid));

        new ChatCommandAstBackfill(repository, new LegacyTemplateConverter()).run();

        assertThat(malformed.getAst()).isNull();
        assertThat(valid.getAst()).contains("\"type\":\"context-get\"");
        verify(repository).saveAll(List.of(valid));
    }
}
