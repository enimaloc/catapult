package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslGenerator;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
public class ApiChatCommandDslController {

    private static final CommandDslParser PARSER = new CommandDslParser();
    private static final CommandDslGenerator GENERATOR = new CommandDslGenerator();
    private static final NodeJsonCodec CODEC = new NodeJsonCodec();

    private final ServiceFunctionRegistry serviceFunctionRegistry;
    private final JsCompiler jsCompiler;
    private final PlaceholderResolver placeholderResolver;
    private final ChatCommandSettingRepository settingRepository;
    private final UserAccountRepository userAccountRepository;
    private final TwDefinitionRepository twDefinitionRepository;

    public ApiChatCommandDslController(ServiceFunctionRegistry serviceFunctionRegistry, JsCompiler jsCompiler,
                                        PlaceholderResolver placeholderResolver,
                                        ChatCommandSettingRepository settingRepository,
                                        UserAccountRepository userAccountRepository,
                                        TwDefinitionRepository twDefinitionRepository) {
        this.serviceFunctionRegistry = serviceFunctionRegistry;
        this.jsCompiler = jsCompiler;
        this.placeholderResolver = placeholderResolver;
        this.settingRepository = settingRepository;
        this.userAccountRepository = userAccountRepository;
        this.twDefinitionRepository = twDefinitionRepository;
    }

    public record ServiceFunctionDto(String namespace, String name, List<String> parameterNames,
                                      List<String> returnKeys, List<String> optionalParameterNames,
                                      boolean isAction) {}

    public record TwOptionDto(String id, String label) {}

    public record CatalogDto(List<String> contextPaths, List<ServiceFunctionDto> serviceFunctions,
                              List<String> settingKeys, List<TwOptionDto> knownTws) {}

    /**
     * The settings block's dropdown needs the streamer's own saved keys (unlike context paths,
     * which are a fixed known catalog) — resolved best-effort so the rest of the catalog (context
     * paths, service functions) still renders even for a caller the JWT can't be matched to a
     * {@link UserAccount} for (e.g. an incomplete test JWT).
     */
    @GetMapping("/api/chat-commands/dsl/catalog")
    public CatalogDto catalog(@AuthenticationPrincipal Jwt jwt) {
        List<ServiceFunctionDto> functions = serviceFunctionRegistry.all().stream()
            .map(f -> new ServiceFunctionDto(f.namespace(), f.name(), f.parameterNames(), f.returnKeys(),
                f.optionalParameterNames(), f.isAction()))
            .toList();
        List<String> settingKeys = currentUser(jwt)
            .map(user -> settingRepository.findByUser(user).stream()
                .map(ChatCommandSetting::getKey).sorted().toList())
            .orElse(List.of());
        List<TwOptionDto> knownTws = twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
            .map(d -> new TwOptionDto(d.getId(), d.getLabel()))
            .toList();
        return new CatalogDto(List.copyOf(PlaceholderResolver.KNOWN_PATHS), functions, settingKeys, knownTws);
    }

    private Optional<UserAccount> currentUser(Jwt jwt) {
        if (jwt == null) return Optional.empty();
        String twitchId = jwt.getClaimAsString("twitchId");
        return userAccountRepository.findByTwitchId(twitchId);
    }

    @PostMapping("/api/chat-commands/dsl/text-to-ast")
    public Map<String, String> textToAst(@RequestBody Map<String, String> body) {
        try {
            return Map.of("ast", CODEC.toJson(PARSER.parse(body.get("text"))));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/api/chat-commands/dsl/ast-to-text")
    public Map<String, String> astToText(@RequestBody Map<String, String> body) {
        try {
            return Map.of("text", GENERATOR.generate(CODEC.fromJson(body.get("ast"))));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Backs the read-only "JS généré" tab — same compiler used at actual dispatch time. */
    @PostMapping("/api/chat-commands/dsl/ast-to-js")
    public Map<String, String> astToJs(@RequestBody Map<String, String> body) {
        try {
            return Map.of("js", jsCompiler.compile(CODEC.fromJson(body.get("ast"))));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

}
