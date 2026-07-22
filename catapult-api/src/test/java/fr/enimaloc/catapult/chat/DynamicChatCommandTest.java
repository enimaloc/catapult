package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.service.GameContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicChatCommandTest {

    private PlaceholderResolver newPlaceholderResolver() {
        TwPlaceholderRegistry twRegistry = mock(TwPlaceholderRegistry.class);
        when(twRegistry.getKnownPaths()).thenReturn(Set.of());
        return new PlaceholderResolver(new SimpleMeterRegistry(), twRegistry);
    }

    @Test
    void executesThroughAstPipeline() {
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!game");
        definition.setEnabled(true);
        definition.setTemplate("Now playing {game#name}!");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("Now playing {game#name}!")));

        GameContext ctx = mock(GameContext.class);
        when(ctx.name()).thenReturn("Valorant");
        GameContextService gameContextService = mock(GameContextService.class);
        when(gameContextService.get(null)).thenReturn(Optional.of(ctx));

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), gameContextService,
            newPlaceholderResolver(), Locale.FRENCH);

        Object result = command.execute(null, List.of());

        assertThat(result).isEqualTo("Now playing Valorant!");
    }

    @Test
    void disabledCommandReturnsNull() {
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setEnabled(false);
        definition.setTemplate("hi");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("hi")));

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), mock(GameContextService.class),
            newPlaceholderResolver(), Locale.FRENCH);

        assertThat(command.execute(null, List.of())).isNull();
    }

    @Test
    void nullAstFallsBackToParsingTheLegacyTemplate() {
        // Definitions created before the AST backfill ran (or via a code path
        // that doesn't populate `ast` at creation time, e.g. preset instantiation)
        // must still execute correctly by parsing the legacy template on the fly.
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!game");
        definition.setEnabled(true);
        definition.setTemplate("Now playing {game#name}!");
        definition.setAst(null);

        GameContext ctx = mock(GameContext.class);
        when(ctx.name()).thenReturn("Dota 2");
        GameContextService gameContextService = mock(GameContextService.class);
        when(gameContextService.get(null)).thenReturn(Optional.of(ctx));

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), gameContextService,
            newPlaceholderResolver(), Locale.FRENCH);

        Object result = command.execute(null, List.of());

        assertThat(result).isEqualTo("Now playing Dota 2!");
    }
}
