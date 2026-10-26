package fr.enimaloc.catapult.api.chatcommand;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslGenerator;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.common.dto.chatcommand.CatalogDto;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandDslCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Bridges the browser's Blocks tab to the (Java-only) text DSL grammar so the editor modal
 * can convert text&lt;-&gt;AST without duplicating {@link CommandDslParser}/{@link CommandDslGenerator}
 * in JavaScript, and serves the block/dropdown catalog so the server stays the single source
 * of truth for what a streamer can build — the client never hardcodes known context paths or
 * service functions, it renders whatever this endpoint reports. Also renders the read-only
 * "JS généré" tab's content (Phase 1 scope only — see design spec's Block Editor section:
 * editing the generated JS is explicitly Phase 2, reserved behind the disabled "Éjecter" button).
 */
@RestController
@RequiredArgsConstructor
public class ApiChatCommandDslController {

    private static final CommandDslParser PARSER = new CommandDslParser();
    private static final CommandDslGenerator GENERATOR = new CommandDslGenerator();
    private static final NodeJsonCodec CODEC = new NodeJsonCodec();

    private final ChatCommandDslCatalog dslCatalog;
    private final ApiUserResolver userResolver;
    private final JsCompiler jsCompiler;

    /**
     * The settings block's dropdown needs the streamer's own saved keys, so the caller is resolved best-effort:
     * the rest of the catalog still renders for a caller the JWT can't be matched to an account for.
     */
    @GetMapping(ApiPaths.INTERNAL + "/chat-command-dsl/catalog")
    public CatalogDto catalog(@AuthenticationPrincipal Jwt jwt) {
        return dslCatalog.catalog(userResolver.viewerByTwitchIdIfAny(jwt));
    }

    @PostMapping(ApiPaths.INTERNAL + "/chat-command-dsl/text-to-ast")
    public Map<String, String> textToAst(@RequestBody Map<String, String> body) {
        try {
            return Map.of("ast", CODEC.toJson(PARSER.parse(body.get("text"))));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping(ApiPaths.INTERNAL + "/chat-command-dsl/ast-to-text")
    public Map<String, String> astToText(@RequestBody Map<String, String> body) {
        try {
            return Map.of("text", GENERATOR.generate(CODEC.fromJson(body.get("ast"))));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Backs the read-only "JS généré" tab — same compiler used at actual dispatch time. */
    @PostMapping(ApiPaths.INTERNAL + "/chat-command-dsl/ast-to-js")
    public Map<String, String> astToJs(@RequestBody Map<String, String> body) {
        try {
            return Map.of("js", jsCompiler.compile(CODEC.fromJson(body.get("ast"))));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

}
