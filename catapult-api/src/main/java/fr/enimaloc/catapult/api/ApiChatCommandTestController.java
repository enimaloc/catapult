package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.MockingServiceFunctionRegistry;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.chat.command.trace.ExecutionTrace;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Backs the "Tester" button in the command editor modal: compiles and runs a command's AST
 * (persisted, or an in-progress unsaved edit passed by the client) in the sandbox with
 * author-supplied placeholder overrides, and returns both the resulting output and a
 * step-by-step {@link ExecutionTrace} for debugging.
 *
 * <p>Requires the same auth/ownership/experiment-gate checks as {@link ApiChatCommandsController}
 * — without them this endpoint would let anyone compile and run arbitrary JS in the sandbox
 * against any user's command row by ID (IDOR), since the client can also supply an arbitrary
 * {@code ast} to compile.
 */
@RestController
@RequiredArgsConstructor
public class ApiChatCommandTestController {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(3);
    private static final NodeJsonCodec CODEC = new NodeJsonCodec();
    private static final CommandDslParser LEGACY_PARSER = new CommandDslParser();
    private static final ObjectMapper MOCK_MAPPER = JsonMapper.builder().build();

    private final ChatCommandDefinitionRepository repository;
    private final UserAccountRepository userAccountRepository;
    private final ExperimentService experimentService;
    private final JsCompiler jsCompiler;
    private final SandboxExecutor sandboxExecutor;
    private final ServiceFunctionRegistry serviceFunctionRegistry;
    private final ChatCommandSettingRepository settingRepository;
    private final PlaceholderResolver placeholderResolver;

    @PostMapping("/api/chat-commands/{id}/test")
    public Map<String, Object> test(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @RequestBody Map<String, Object> body) {
        UserAccount user = currentUser(jwt);
        gate(user);
        ChatCommandDefinition definition = repository.findById(id)
            .filter(d -> d.getUser().getId().equals(user.getId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown command " + id));

        @SuppressWarnings("unchecked")
        Map<String, String> overrides = (Map<String, String>) body.getOrDefault("overrides", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, String> settingOverrides = (Map<String, String>) body.getOrDefault("settings", Map.of());
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) body.getOrDefault("args", List.of());
        @SuppressWarnings("unchecked")
        Map<String, String> rawServiceMocks = (Map<String, String>) body.getOrDefault("serviceMocks", Map.of());
        ServiceFunctionRegistry registry = rawServiceMocks.isEmpty() ? serviceFunctionRegistry
            : new MockingServiceFunctionRegistry(serviceFunctionRegistry, parseServiceMocks(rawServiceMocks));

        // Unlike context placeholders (game#name etc.), which have no real value at all outside
        // a live stream and so always need a manual test override, settings are real persisted
        // per-streamer settings — the Tester should reflect what's actually saved by default,
        // with the "settings" request field only overriding specific keys for one-off "what if"
        // testing (mirrors DynamicChatCommand#execute's real resolution, plus that override layer).
        Map<String, String> settings = new HashMap<>(settingRepository.findByUser(user).stream()
            .collect(Collectors.toMap(ChatCommandSetting::getKey, ChatCommandSetting::getValue)));
        settings.putAll(settingOverrides);

        String js = resolveJs(body, definition);

        // A placeholder the caller didn't supply a test value for must resolve to "" like the
        // real dispatch path (DynamicChatCommand#resolvePlaceholder) does — overrides::get
        // alone would hand GraalJS a Java null, which string-concatenates as the literal "null".
        ExecutionTrace trace = sandboxExecutor.executeWithTrace(js,
            path -> overrides.getOrDefault(path, ""),
            name -> {
                if ("fallbacks".equals(name)) return List.copyOf(overrides.values());
                if ("args".equals(name)) return List.copyOf(args);
                // Unlike activeTws/ownCommands/gameDlcs/similarGames, allTws doesn't depend on a
                // live game context — safe to resolve for real in the Tester too.
                if ("allTws".equals(name)) return List.copyOf(placeholderResolver.allTwOptions());
                return List.of();
            },
            registry, user, key -> settings.getOrDefault(key, ""), TEST_TIMEOUT);

        return Map.of(
            "output", trace.finalOutput(),
            "trace", trace.entries().stream().map(e -> Map.of(
                "nodeType", e.nodeType(),
                "description", e.description(),
                "resolvedValue", e.resolvedValue() == null ? "" : e.resolvedValue(),
                "error", e.error()
            )).toList()
        );
    }

    /**
     * The editor's Blocks/Text views mutate the AST before it's saved, so the Tester button
     * must run against the client's in-progress {@code ast} rather than the persisted one —
     * otherwise "Test" would silently ignore unsaved edits. Falls back to the persisted AST,
     * or a fresh parse of the legacy template for rows the AST backfill hasn't reached yet
     * (mirrors {@code DynamicChatCommand#decodeAst}).
     */
    private CommandAst resolveAst(Map<String, Object> body, ChatCommandDefinition definition) {
        Object astJson = body.get("ast");
        if (astJson instanceof String s && !s.isBlank()) {
            return CODEC.fromJson(s);
        }
        if (definition.getAst() != null) {
            return CODEC.fromJson(definition.getAst());
        }
        return LEGACY_PARSER.parse(definition.getTemplate());
    }

    /**
     * "Eject to JS" (Phase 2): an in-progress edit in the JS tab's textarea (unsaved) takes
     * priority, then the persisted {@code ejectedJs}, then the normal AST-compiled path —
     * mirroring {@code DynamicChatCommand#execute}'s dispatch-time precedence, so the Tester
     * button reflects exactly what would actually run.
     */
    private String resolveJs(Map<String, Object> body, ChatCommandDefinition definition) {
        Object ejectedJs = body.get("ejectedJs");
        if (ejectedJs instanceof String s && !s.isBlank()) {
            return s;
        }
        if (definition.getEjectedJs() != null && !definition.getEjectedJs().isBlank()) {
            return definition.getEjectedJs();
        }
        return jsCompiler.compileWithTrace(resolveAst(body, definition));
    }

    /**
     * A mocked service call's test value is free-form text in the editor — parsed as JSON when
     * possible, so a map/DTO-returning function (e.g. {@code steam#getGame}) can be mocked with
     * {@code {"short_description":"..."}}, or a scalar one (e.g. {@code tw#has}) with just {@code
     * true} — falling back to the raw string otherwise (plain text isn't valid JSON, so this is
     * also what a bare string mock value like {@code Cyberpunk 2077} ends up as).
     */
    private Map<String, Object> parseServiceMocks(Map<String, String> rawServiceMocks) {
        Map<String, Object> parsed = new HashMap<>();
        rawServiceMocks.forEach((key, value) -> {
            try {
                parsed.put(key, MOCK_MAPPER.readValue(value, Object.class));
            } catch (RuntimeException e) {
                parsed.put(key, value);
            }
        });
        return parsed;
    }

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userAccountRepository.findByTwitchId(twitchId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private void gate(UserAccount user) {
        if (!experimentService.evaluateGate(user, ApiChatCommandsController.EXPERIMENT_KEY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature not enabled");
        }
    }
}
