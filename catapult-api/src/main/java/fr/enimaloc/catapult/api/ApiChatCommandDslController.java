package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslGenerator;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.IgdbService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final PlaceholderResolver placeholderResolver;
    private final ChatCommandSettingRepository settingRepository;
    private final UserAccountRepository userAccountRepository;

    public ApiChatCommandDslController(ServiceFunctionRegistry serviceFunctionRegistry, JsCompiler jsCompiler,
                                        IgdbService igdbService, IgdbGameDetailsService igdbGameDetailsService,
                                        PlaceholderResolver placeholderResolver,
                                        ChatCommandSettingRepository settingRepository,
                                        UserAccountRepository userAccountRepository) {
        this.serviceFunctionRegistry = serviceFunctionRegistry;
        this.jsCompiler = jsCompiler;
        this.igdbService = igdbService;
        this.igdbGameDetailsService = igdbGameDetailsService;
        this.placeholderResolver = placeholderResolver;
        this.settingRepository = settingRepository;
        this.userAccountRepository = userAccountRepository;
    }

    public record ServiceFunctionDto(String namespace, String name, List<String> parameterNames,
                                      List<String> returnKeys, List<String> optionalParameterNames,
                                      boolean isAction) {}

    public record CatalogDto(List<String> contextPaths, List<ServiceFunctionDto> serviceFunctions,
                              List<String> settingKeys) {}

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
        return new CatalogDto(List.copyOf(PlaceholderResolver.KNOWN_PATHS), functions, settingKeys);
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

    /**
     * Backs the Tester panel's "import a game from IGDB" search — a non-admin-gated
     * equivalent of {@link ApiAdminIgdbController#search}, since regular streamers (not just
     * admins) use the chat-command editor.
     */
    @GetMapping("/api/chat-commands/dsl/igdb-search")
    public List<IgdbService.IgdbGame> igdbSearch(@RequestParam(defaultValue = "") String q) {
        if (q.isBlank()) return List.of();
        return igdbService.searchGames(q);
    }

    /**
     * Resolves every {@code game#*} placeholder for the given IGDB game exactly as
     * {@link PlaceholderResolver} would at real dispatch time, so the "Valeurs de test" fields
     * can be pre-filled from a picked game instead of typed by hand. {@code game#agerating}
     * comes from a separate content-labels subsystem (not plain IGDB details) and {@code
     * game#store#url} depends on which store the streamer is actually live on — both are left
     * for manual entry.
     */
    @GetMapping("/api/chat-commands/dsl/igdb-preview")
    public Map<String, String> igdbPreview(@RequestParam String id, @RequestParam String name, Locale locale) {
        IgdbGameDetails details = igdbGameDetailsService.getDetails(id).orElse(null);
        String summary = details != null ? details.getSummary() : null;
        LocalDate releaseDate = (details != null && details.getFirstReleaseDate() != null)
            ? details.getFirstReleaseDate().atZone(ZoneOffset.UTC).toLocalDate() : null;
        Map<String, String> stores = (details != null && details.getWebsites() != null)
            ? details.getWebsites() : Map.of();
        String slug = details != null ? details.getSlug() : null;
        Double rating = details != null ? details.getRating() : null;
        Double criticRating = details != null ? details.getAggregatedRating() : null;
        List<String> platforms = (details != null && details.getPlatforms() != null)
            ? details.getPlatforms() : List.of();
        List<String> dlcNames = (details != null && details.getDlcNames() != null)
            ? details.getDlcNames() : List.of();
        List<String> similarGameNames = (details != null && details.getSimilarGameNames() != null)
            ? details.getSimilarGameNames() : List.of();

        GameContext ctx = new GameContext(null, id, name, summary, releaseDate, stores, null, slug,
            Set.of(), Map.of(), null, rating, criticRating, platforms, dlcNames, similarGameNames);

        Map<String, String> result = new LinkedHashMap<>();
        for (String path : PlaceholderResolver.KNOWN_PATHS) {
            if (!path.startsWith("game#")) continue;
            String value = placeholderResolver.lookupRaw(ctx, path, locale);
            if (value != null) result.put(path, value);
        }
        return result;
    }
}
