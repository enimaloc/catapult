package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallExpr;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
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
            newPlaceholderResolver(), Locale.FRENCH, mock(ChatCommandSettingRepository.class));

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
            newPlaceholderResolver(), Locale.FRENCH, mock(ChatCommandSettingRepository.class));

        assertThat(command.execute(null, List.of())).isNull();
    }

    @Test
    void ejectedJsRunsDirectlyInsteadOfCompilingTheAst() {
        // A non-null ejectedJs (Phase 2's "eject to JS") must run as-is — the ast/template
        // stay untouched (reversibility), but dispatch bypasses the compiler entirely.
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!custom");
        definition.setEnabled(true);
        definition.setTemplate("this should never run");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("this should never run")));
        definition.setEjectedJs("return \"hand-written output\";");

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), mock(GameContextService.class),
            newPlaceholderResolver(), Locale.FRENCH, mock(ChatCommandSettingRepository.class));

        Object result = command.execute(null, List.of());

        assertThat(result).isEqualTo("hand-written output");
    }

    @Test
    void blankEjectedJsFallsBackToTheAstPipeline() {
        // A blank (not null) ejectedJs — e.g. after clearing the eject textarea without
        // reverting explicitly — must not be treated as "ejected".
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!game");
        definition.setEnabled(true);
        definition.setTemplate("Now playing {game#name}!");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("Now playing {game#name}!")));
        definition.setEjectedJs("   ");

        GameContext ctx = mock(GameContext.class);
        when(ctx.name()).thenReturn("Valorant");
        GameContextService gameContextService = mock(GameContextService.class);
        when(gameContextService.get(null)).thenReturn(Optional.of(ctx));

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), gameContextService,
            newPlaceholderResolver(), Locale.FRENCH, mock(ChatCommandSettingRepository.class));

        Object result = command.execute(null, List.of());

        assertThat(result).isEqualTo("Now playing Valorant!");
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
            newPlaceholderResolver(), Locale.FRENCH, mock(ChatCommandSettingRepository.class));

        Object result = command.execute(null, List.of());

        assertThat(result).isEqualTo("Now playing Dota 2!");
    }

    @Test
    void invokingUserReachesServiceFunctionCalls() {
        // A command whose AST calls a service function (e.g. a future "twitch#getUser"-style
        // lookup scoped to "the current streamer") must have the invoking UserAccount threaded
        // all the way from ChatCommand.execute(user, args) through DynamicChatCommand,
        // SandboxExecutor's ctx.call, down to ServiceFunction#invoke.
        UserAccount user = new UserAccount();

        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!whoami");
        definition.setEnabled(true);
        definition.setTemplate("");
        CommandAst ast = new CommandAst(List.of(
            new PrintStatement(new ServiceCallExpr("test", "whoAmI", List.of()))));
        definition.setAst(new NodeJsonCodec().toJson(ast));

        GameContextService gameContextService = mock(GameContextService.class);
        when(gameContextService.get(user)).thenReturn(Optional.of(GameContext.empty()));

        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "test"; }
            @Override public String name() { return "whoAmI"; }
            @Override public List<String> parameterNames() { return List.of(); }
            @Override public Object invoke(UserAccount boundUser, Object[] args) {
                return boundUser == user ? "same-user" : "different-user";
            }
        });

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), registry, gameContextService,
            newPlaceholderResolver(), Locale.FRENCH, mock(ChatCommandSettingRepository.class));

        Object result = command.execute(user, List.of());

        assertThat(result).isEqualTo("same-user");
    }

    @Test
    void resolvesCtxSettingsFromTheRealRepositoryForTheInvokingUser() {
        UserAccount user = new UserAccount();

        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!lang");
        definition.setEnabled(true);
        definition.setTemplate("{ctx.settings.language}");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("{ctx.settings.language}")));

        fr.enimaloc.catapult.repository.ChatCommandSettingRepository settingRepository =
            mock(fr.enimaloc.catapult.repository.ChatCommandSettingRepository.class);
        fr.enimaloc.catapult.domain.ChatCommandSetting setting = new fr.enimaloc.catapult.domain.ChatCommandSetting();
        setting.setKey("language");
        setting.setValue("fr");
        when(settingRepository.findByUser(user)).thenReturn(List.of(setting));

        GameContextService gameContextService = mock(GameContextService.class);
        when(gameContextService.get(user)).thenReturn(Optional.empty());

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), gameContextService,
            newPlaceholderResolver(), Locale.FRENCH, settingRepository);

        Object result = command.execute(user, List.of());

        assertThat(result).isEqualTo("fr");
    }

    @Test
    void ctxSettingsResolvesToEmptyStringWhenKeyIsNotSet() {
        UserAccount user = new UserAccount();

        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!lang");
        definition.setEnabled(true);
        definition.setTemplate("[{ctx.settings.language}]");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("[{ctx.settings.language}]")));

        fr.enimaloc.catapult.repository.ChatCommandSettingRepository settingRepository =
            mock(fr.enimaloc.catapult.repository.ChatCommandSettingRepository.class);
        when(settingRepository.findByUser(user)).thenReturn(List.of());

        GameContextService gameContextService = mock(GameContextService.class);
        when(gameContextService.get(user)).thenReturn(Optional.empty());

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), gameContextService,
            newPlaceholderResolver(), Locale.FRENCH, settingRepository);

        Object result = command.execute(user, List.of());

        assertThat(result).isEqualTo("[]");
    }

    @Test
    void argResolvesTheRealInvocationArgument() {
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!shoutout");
        definition.setEnabled(true);
        definition.setTemplate("Go check out {arg(0)}!");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("Go check out {arg(0)}!")));

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), mock(GameContextService.class),
            newPlaceholderResolver(), Locale.FRENCH, mock(fr.enimaloc.catapult.repository.ChatCommandSettingRepository.class));

        Object result = command.execute(null, List.of("myfriend"));

        assertThat(result).isEqualTo("Go check out myfriend!");
    }

    @Test
    void argResolvesToEmptyStringWhenTheIndexWasNotSupplied() {
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!shoutout");
        definition.setEnabled(true);
        definition.setTemplate("[{arg(0)}]");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("[{arg(0)}]")));

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), mock(GameContextService.class),
            newPlaceholderResolver(), Locale.FRENCH, mock(fr.enimaloc.catapult.repository.ChatCommandSettingRepository.class));

        Object result = command.execute(null, List.of());

        assertThat(result).isEqualTo("[]");
    }

    @Test
    void forEachOverArgsIteratesAllSuppliedArguments() {
        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setName("!listargs");
        definition.setEnabled(true);
        definition.setTemplate("{for a in args}[{a}]{/for}");
        definition.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("{for a in args}[{a}]{/for}")));

        DynamicChatCommand command = new DynamicChatCommand(definition, new JsCompiler(),
            new SandboxExecutor(), new ServiceFunctionRegistry(), mock(GameContextService.class),
            newPlaceholderResolver(), Locale.FRENCH, mock(fr.enimaloc.catapult.repository.ChatCommandSettingRepository.class));

        Object result = command.execute(null, List.of("one", "two"));

        assertThat(result).isEqualTo("[one][two]");
    }
}
