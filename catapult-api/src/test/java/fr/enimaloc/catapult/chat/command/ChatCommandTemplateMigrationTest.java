package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandTemplateMigrationTest {

    private final NodeJsonCodec codec = new NodeJsonCodec();
    private final CommandDslParser parser = new CommandDslParser();

    @Test
    void regeneratesTemplateFromAstUsingTheCurrentCanonicalSpelling() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition stale = new ChatCommandDefinition();
        stale.setTemplate("Now playing {game#name}!");
        stale.setAst(codec.toJson(parser.parse("Now playing {game#name}!")));
        when(repository.findAll()).thenReturn(List.of(stale));

        new ChatCommandTemplateMigration(repository).run();

        assertThat(stale.getTemplate()).isEqualTo("Now playing {ctx.game.name}!");
        verify(repository).saveAll(List.of(stale));
    }

    @Test
    void leavesAlreadyCanonicalTemplatesUntouched() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition current = new ChatCommandDefinition();
        current.setTemplate("Now playing {ctx.game.name}!");
        current.setAst(codec.toJson(parser.parse("Now playing {ctx.game.name}!")));
        when(repository.findAll()).thenReturn(List.of(current));

        new ChatCommandTemplateMigration(repository).run();

        assertThat(current.getTemplate()).isEqualTo("Now playing {ctx.game.name}!");
        verify(repository).saveAll(List.of());
    }

    @Test
    void skipsRowsWithNoAstOrMalformedAstWithoutFailingTheWholeBatch() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition noAst = new ChatCommandDefinition();
        noAst.setName("no-ast");
        noAst.setTemplate("stale text");
        ChatCommandDefinition malformedAst = new ChatCommandDefinition();
        malformedAst.setName("malformed");
        malformedAst.setTemplate("stale text");
        malformedAst.setAst("not json");
        when(repository.findAll()).thenReturn(List.of(noAst, malformedAst));

        new ChatCommandTemplateMigration(repository).run();

        assertThat(noAst.getTemplate()).isEqualTo("stale text");
        assertThat(malformedAst.getTemplate()).isEqualTo("stale text");
        verify(repository).saveAll(List.of());
    }
}
