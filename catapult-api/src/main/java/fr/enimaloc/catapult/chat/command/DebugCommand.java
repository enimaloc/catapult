package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.ChatCommand;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParseException;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutionException;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.RequiredArgsConstructor;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * !debug [path] — Affiche la valeur résolue de tous les placeholders connus
 * (ou d'un seul si précisé) contre le GameContext courant du channel.
 * <p>
 * !debug tpl &lt;template text&gt; — parse/compile/exécute un template DSL à la volée à travers
 * le même pipeline sandboxé qu'une {@link fr.enimaloc.catapult.chat.DynamicChatCommand} réelle,
 * sans avoir à créer une {@link fr.enimaloc.catapult.domain.ChatCommandDefinition} au préalable.
 * <p>
 * !debug js &lt;code&gt; — évalue du JS brut avec un accès host COMPLET (non sandboxé) : sûr
 * uniquement parce que {@link #isOwnerOnly()} garantit qu'aucun autre utilisateur que l'owner
 * de l'application ne peut jamais atteindre {@link #execute}.
 * <p>
 * Réservée à l'owner de l'application ({@code isOwnerOnly}). Volontairement
 * absente du {@code ChatCommandPresetCatalog} et sans lookup de
 * {@code ChatCommandDefinition} : invisible dans l'UI streamer et
 * non-désactivable.
 */
@Component
@RequiredArgsConstructor
public class DebugCommand implements ChatCommand {

    static final String EMPTY_VALUE = "∅";
    private static final Duration TEMPLATE_TIMEOUT = Duration.ofSeconds(3);

    private final PlaceholderResolver placeholderResolver;
    private final TwPlaceholderRegistry twPlaceholderRegistry;
    private final GameContextService gameContextService;
    private final JsCompiler jsCompiler;
    private final SandboxExecutor sandboxExecutor;
    private final ServiceFunctionRegistry serviceFunctionRegistry;
    private final ChatCommandSettingRepository settingRepository;

    // Not a Spring bean (see DynamicChatCommand's own LEGACY_PARSER) — constructed directly like
    // every other caller of the text DSL parser.
    private final CommandDslParser dslParser = new CommandDslParser();

    @Override
    public String getName() {
        return "!debug";
    }

    @Override
    public ChatCommandEvent.SenderRole getRequiredPermission() {
        return ChatCommandEvent.SenderRole.VIEWERS;
    }

    @Override
    public boolean isOwnerOnly() {
        return true;
    }

    @Override
    public Object execute(UserAccount user, List<String> args) {
        if (!args.isEmpty() && "tpl".equals(args.get(0))) {
            return executeTemplate(user, String.join(" ", args.subList(1, args.size())));
        }
        if (!args.isEmpty() && "js".equals(args.get(0))) {
            return executeRawJs(String.join(" ", args.subList(1, args.size())));
        }

        GameContext ctx = gameContextService.get(user).orElse(GameContext.empty());
        // Même locale figée que DynamicCommandResolver (pas de locale par user).
        Locale locale = Locale.FRANCE;

        if (!args.isEmpty()) {
            String path = args.get(0);
            if (!knownPaths().contains(path)) {
                return "Placeholder inconnu : " + path;
            }
            return path + "=" + resolveOrEmpty(ctx, path, locale);
        }

        return knownPaths().stream()
            .map(path -> path + "=" + resolveOrEmpty(ctx, path, locale))
            .collect(Collectors.joining("; "));
    }

    /**
     * Runs an ad-hoc template through the exact same parse/compile/sandbox pipeline a saved
     * {@link fr.enimaloc.catapult.chat.DynamicChatCommand} uses ({@link CommandDslParser} →
     * {@link JsCompiler} → {@link SandboxExecutor}) — still fully sandboxed, no host access.
     * There's no saved {@code ChatCommandDefinition} to read fallbacks/args from here, so
     * {@code arg(N)}/{@code list(...)} always resolve to their empty defaults.
     */
    private Object executeTemplate(UserAccount user, String template) {
        if (template.isBlank()) {
            return "Usage: !debug tpl <template text>";
        }
        GameContext ctx = gameContextService.get(user).orElse(GameContext.empty());
        Map<String, String> settings = settingRepository.findByUser(user).stream()
            .collect(Collectors.toMap(ChatCommandSetting::getKey, ChatCommandSetting::getValue));
        try {
            CommandAst ast = dslParser.parse(template);
            String js = jsCompiler.compile(ast);
            String output = sandboxExecutor.execute(js,
                path -> resolvePlaceholderRaw(ctx, path),
                name -> List.of(),
                serviceFunctionRegistry,
                user,
                key -> settings.getOrDefault(key, ""),
                TEMPLATE_TIMEOUT);
            return output.isBlank() ? EMPTY_VALUE : output;
        } catch (CommandDslParseException e) {
            return "Parse error: " + e.getMessage();
        } catch (SandboxExecutionException e) {
            return "Execution error: " + e.getMessage();
        }
    }

    /**
     * Evaluates raw JS with {@code allowAllAccess(true)} — full host class lookup, IO, native
     * access and thread creation, i.e. no sandbox at all, unlike every other JS execution path
     * in this codebase ({@link SandboxExecutor} always restricts to {@code HostAccess.EXPLICIT}
     * + {@code IOAccess.NONE}). This is only safe because {@link #isOwnerOnly()} is checked by
     * {@link fr.enimaloc.catapult.chat.CommandRegistry#dispatch} (against the app's own Twitch
     * id, not a role/permission tier) before {@link #execute} is ever called — nobody else can
     * reach this method.
     */
    private Object executeRawJs(String code) {
        if (code.isBlank()) {
            return "Usage: !debug js <code>";
        }
        try (Context context = Context.newBuilder("js").allowAllAccess(true).build()) {
            Value result = context.eval("js", code);
            return result.isNull() ? EMPTY_VALUE : result.toString();
        } catch (PolyglotException e) {
            return "JS error: " + e.getMessage();
        }
    }

    private TreeSet<String> knownPaths() {
        TreeSet<String> paths = new TreeSet<>(PlaceholderResolver.KNOWN_PATHS);
        twPlaceholderRegistry.getKnownPaths().forEach(id -> paths.add("tw#" + id));
        return paths;
    }

    private String resolveOrEmpty(GameContext ctx, String path, Locale locale) {
        String value = placeholderResolver.lookupRaw(ctx, path, locale);
        return value == null || value.isBlank() ? EMPTY_VALUE : value;
    }

    /** Same semantics as {@code DynamicChatCommand#resolvePlaceholder} (blank/null → {@code ""},
     *  never the {@link #EMPTY_VALUE} sentinel) — a template's own {@code {if x != ""}} checks
     *  must see exactly what they'd see in production, not a debug-only placeholder marker. */
    private String resolvePlaceholderRaw(GameContext ctx, String path) {
        String value = placeholderResolver.lookupRaw(ctx, path, Locale.FRANCE);
        return value != null ? value : "";
    }
}
