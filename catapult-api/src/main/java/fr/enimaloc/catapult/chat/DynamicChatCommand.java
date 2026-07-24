package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutionException;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes a data-driven chat command by decoding its {@link ChatCommandDefinition#getAst()}
 * (Task 8's {@link NodeJsonCodec}), compiling it to JS (Task 9's {@link JsCompiler}) and running
 * that JS in the GraalJS sandbox (Tasks 10-11's {@link SandboxExecutor}), bound to the
 * whitelisted {@link ServiceFunctionRegistry} service functions.
 *
 * <p>Placeholder lookups ({@code {game#name}} etc.) are still delegated to the real
 * {@link PlaceholderResolver} bean — it owns the placeholder catalog (including TW placeholders
 * and the Micrometer miss/usage counters), so re-implementing that logic here (or constructing a
 * throwaway {@code PlaceholderResolver} with null dependencies just to reach {@code lookupRaw})
 * would either duplicate behavior or silently drop metrics. Injecting the managed bean keeps a
 * single source of truth for "what does this placeholder path resolve to".
 */
@Slf4j
public class DynamicChatCommand implements ChatCommand {

    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(3);
    private static final NodeJsonCodec CODEC = new NodeJsonCodec();
    private static final CommandDslParser LEGACY_PARSER = new CommandDslParser();

    private final ChatCommandDefinition definition;
    private final JsCompiler jsCompiler;
    private final SandboxExecutor sandboxExecutor;
    private final ServiceFunctionRegistry serviceFunctionRegistry;
    private final GameContextService gameContextService;
    private final PlaceholderResolver placeholderResolver;
    private final Locale streamerLocale;

    public DynamicChatCommand(ChatCommandDefinition definition, JsCompiler jsCompiler,
                               SandboxExecutor sandboxExecutor, ServiceFunctionRegistry serviceFunctionRegistry,
                               GameContextService gameContextService, PlaceholderResolver placeholderResolver,
                               Locale streamerLocale) {
        this.definition = definition;
        this.jsCompiler = jsCompiler;
        this.sandboxExecutor = sandboxExecutor;
        this.serviceFunctionRegistry = serviceFunctionRegistry;
        this.gameContextService = gameContextService;
        this.placeholderResolver = placeholderResolver;
        this.streamerLocale = streamerLocale;
    }

    @Override
    public String getName() {
        return definition.getName();
    }

    @Override
    public ChatCommandEvent.SenderRole getRequiredPermission() {
        return definition.getPermission();
    }

    @Override
    public Object execute(UserAccount user, List<String> args) {
        if (!definition.isEnabled()) return null;

        GameContext ctx = gameContextService.get(user).orElse(GameContext.empty());
        Map<String, String> fallbacks = definition.getFallbacks().stream()
            .collect(Collectors.toMap(fb -> fb.getPlaceholder(), fb -> fb.getFallbackText()));

        // "Eject to JS" (Phase 2): a non-null ejectedJs runs directly, bypassing the AST
        // compiler entirely — same sandbox, same ctx API, just a different JS source.
        String ejected = definition.getEjectedJs();
        String js = (ejected != null && !ejected.isBlank()) ? ejected : jsCompiler.compile(decodeAst());

        try {
            String output = sandboxExecutor.execute(js,
                path -> resolvePlaceholder(path, ctx, fallbacks),
                name -> resolveList(name, fallbacks),
                serviceFunctionRegistry,
                user,
                EXECUTION_TIMEOUT);
            return output.isBlank() ? null : output;
        } catch (SandboxExecutionException e) {
            // Chat commands are best-effort: a broken command must not take down
            // message handling, so we log and fail silently from the bot's perspective.
            log.warn("Chat command {} failed to execute: {}", definition.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Definitions written before {@code ChatCommandAstBackfill} last ran at boot — or created
     * afresh through a path that doesn't populate {@code ast} yet (e.g. preset instantiation,
     * the admin API) — have a null {@code ast}. Rather than fail the command, parse the legacy
     * template on the fly, mirroring what the backfill runner does at startup.
     */
    private CommandAst decodeAst() {
        String astJson = definition.getAst();
        if (astJson != null) return CODEC.fromJson(astJson);
        return LEGACY_PARSER.parse(definition.getTemplate());
    }

    private String resolvePlaceholder(String path, GameContext ctx, Map<String, String> fallbacks) {
        String value = placeholderResolver.lookupRaw(ctx, path, streamerLocale);
        if (value != null && !value.isBlank()) return value;
        // Never surface a Java/JS "null" literal into the rendered chat message —
        // fall back to the configured fallback text, or an empty string.
        return fallbacks.getOrDefault(path, "");
    }

    private List<String> resolveList(String name, Map<String, String> fallbacks) {
        if ("fallbacks".equals(name)) return List.copyOf(fallbacks.values());
        return List.of();
    }
}
