package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.chat.command.trace.ExecutionTrace;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backs the "Tester" button in the command editor modal: compiles and runs a command's AST
 * (persisted, or an in-progress unsaved edit passed by the client) in the sandbox with
 * author-supplied placeholder overrides, and returns both the resulting output and a
 * step-by-step {@link ExecutionTrace} for debugging.
 */
@RestController
@RequiredArgsConstructor
public class ApiChatCommandTestController {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(3);
    private static final NodeJsonCodec CODEC = new NodeJsonCodec();
    private static final CommandDslParser LEGACY_PARSER = new CommandDslParser();

    private final ChatCommandDefinitionRepository repository;
    private final JsCompiler jsCompiler;
    private final SandboxExecutor sandboxExecutor;
    private final ServiceFunctionRegistry serviceFunctionRegistry;

    @PostMapping("/api/chat-commands/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        ChatCommandDefinition definition = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown command " + id));

        @SuppressWarnings("unchecked")
        Map<String, String> overrides = (Map<String, String>) body.getOrDefault("overrides", Map.of());

        CommandAst effectiveAst = resolveAst(body, definition);
        String js = jsCompiler.compile(effectiveAst);

        ExecutionTrace trace = sandboxExecutor.executeWithTrace(js,
            overrides::get, name -> "fallbacks".equals(name) ? List.copyOf(overrides.values()) : List.of(),
            serviceFunctionRegistry, TEST_TIMEOUT);

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
}
