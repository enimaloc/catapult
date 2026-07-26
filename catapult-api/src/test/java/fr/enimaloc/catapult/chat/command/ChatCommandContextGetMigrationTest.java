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

class ChatCommandContextGetMigrationTest {

    private final NodeJsonCodec codec = new NodeJsonCodec();
    private final CommandDslParser parser = new CommandDslParser();

    @Test
    void rewritesAKnownFlatPlaceholderIntoTheEquivalentServiceCall() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition old = new ChatCommandDefinition();
        old.setTemplate("Now playing {game#name}!");
        old.setAst(codec.toJson(parser.parse("Now playing {game#name}!")));
        when(repository.findAll()).thenReturn(List.of(old));

        new ChatCommandContextGetMigration(repository).run();

        assertThat(old.getTemplate()).isEqualTo("Now playing {print igdb#getCurrentGame().name}!");
        verify(repository).saveAll(List.of(old));
    }

    @Test
    void rewritesTwActiveIntoADirectServiceCall() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition old = new ChatCommandDefinition();
        old.setTemplate("TWs: {tw#active}");
        old.setAst(codec.toJson(parser.parse("TWs: {tw#active}")));
        when(repository.findAll()).thenReturn(List.of(old));

        new ChatCommandContextGetMigration(repository).run();

        assertThat(old.getTemplate()).isEqualTo("TWs: {tw#active()}");
        verify(repository).saveAll(List.of(old));
    }

    @Test
    void leavesADynamicPerGameTwPlaceholderUntouched() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition old = new ChatCommandDefinition();
        old.setTemplate("{tw#violence_graphic}");
        old.setAst(codec.toJson(parser.parse("{tw#violence_graphic}")));
        when(repository.findAll()).thenReturn(List.of(old));

        new ChatCommandContextGetMigration(repository).run();

        assertThat(old.getTemplate()).isEqualTo("{tw#violence_graphic}");
        verify(repository).saveAll(List.of());
    }

    @Test
    void leavesAlreadyMigratedCommandsUntouched() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition current = new ChatCommandDefinition();
        current.setTemplate("Now playing {print igdb#getCurrentGame().name}!");
        current.setAst(codec.toJson(parser.parse("Now playing {print igdb#getCurrentGame().name}!")));
        when(repository.findAll()).thenReturn(List.of(current));

        new ChatCommandContextGetMigration(repository).run();

        assertThat(current.getTemplate()).isEqualTo("Now playing {print igdb#getCurrentGame().name}!");
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

        new ChatCommandContextGetMigration(repository).run();

        assertThat(noAst.getTemplate()).isEqualTo("stale text");
        assertThat(malformedAst.getTemplate()).isEqualTo("stale text");
        verify(repository).saveAll(List.of());
    }
}
