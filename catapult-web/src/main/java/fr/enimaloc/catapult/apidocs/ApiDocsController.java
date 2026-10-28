package fr.enimaloc.catapult.apidocs;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse;
import fr.enimaloc.catapult.controller.ModelFiller;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The API documentation, on the site: the page ({@code /docs/api}, and spa.js's fragment of it,
 * {@code /spa/docs/api}) runs Swagger UI on the site's copy of catapult-api's spec ({@code /docs/api/openapi.json},
 * see ApiDocsSpec), and says whether catapult-api answers: rendered undecided, the page asks {@code /api/health}
 * (ApiHealthProbe) itself, so it never waits on the probe on top of the spec. A cached copy is shown with the time it was fetched (worded in the visitor's time zone by
 * api-docs.js, from the instant in {@code data-fetched-at}); without any copy the page says so,
 * still a 200. catapult-api's own Swagger UI addresses, which the site no longer relays, lead here.
 * <p>
 * A logged-in member also gets their keys above Swagger UI ({@code #api-docs-keys-card}): their OBS widget's token
 * (a capability: rendered in the card only, never logged) and their personal API tokens' cards, the full one only
 * while the custom bindings' experiment is open to them; both read off their own channel's page, as its Account and
 * Integrations tabs do. A failing catapult-api leaves the row it feeds unavailable, never the page. A spa.js prefetch
 * ({@code Purpose: prefetch}) asks catapult-api nothing: the card is rendered pending, and api-docs.js fetches it
 * once the page is shown.
 */
@Slf4j
@Controller
public class ApiDocsController {
    static final String PAGE = "docs/api";
    static final String SPEC_URL = "/docs/api/openapi.json";
    static final String SWAGGER_UI = "/_static/swagger-ui/" + ApiDocsAssets.SWAGGER_UI_VERSION;
    private static final DateTimeFormatter FETCHED_AT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    /** The full token's example binding when the member has none of their own (as on the Account tab). */
    static final String EXAMPLE_BINDING = "Art";
    /** The {@code Purpose} spa.js's prefetches carry (spa.js fetchPage). */
    static final String PREFETCH = "prefetch";

    private final ModelFiller filler;
    private final ApiDocsSpec spec;
    private final ApiService apiService;

    @Autowired
    public ApiDocsController(ModelFiller filler, ApiDocsSpec spec, ApiService apiService) {
        this.filler = filler;
        this.spec = spec;
        this.apiService = apiService;
    }

    @GetMapping("/docs/api")
    String page(Model model, Locale locale) throws IOException {
        model.addAttribute("error", false);
        docs(model);
        keys(model, false);
        filler.fill(model, PAGE, locale);
        return "index";
    }

    @GetMapping("/spa/docs/api")
    String fragment(Model model, @RequestHeader(value = "Purpose", required = false) String purpose) {
        docs(model);
        keys(model, PREFETCH.equals(purpose));
        filler.defaultAttr(model, PAGE);
        return "pages/api-docs :: api-docs";
    }

    @GetMapping(SPEC_URL)
    @ResponseBody
    ResponseEntity<String> openApi() {
        return spec.current()
                .map(cached -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .cacheControl(CacheControl.noCache())
                        .body(cached.json()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Retry-After", String.valueOf(ApiDocsSpec.REFRESH.toSeconds()))
                        .cacheControl(CacheControl.noStore())
                        .build());
    }

    /** catapult-api's own Swagger UI, which links and bookmarks still name: moved here for good. */
    @GetMapping({"/swagger-ui.html", "/swagger-ui/index.html"})
    ResponseEntity<Void> swaggerUi() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).location(URI.create("/" + PAGE)).build();
    }

    private void docs(Model model) {
        Optional<ApiDocsSpec.Cached> cached = spec.current();
        Optional<ApiDocsSpec.Cached> older = cached.filter(ApiDocsSpec.Cached::fromCache);
        model.addAttribute("apiDocsAvailable", cached.isPresent());
        // The server's own zone without script; api-docs.js words it in the visitor's (from the instant).
        model.addAttribute("apiDocsCachedAt", older
                .map(copy -> FETCHED_AT.withZone(ZoneId.systemDefault()).format(copy.fetchedAt())).orElse(null));
        model.addAttribute("apiDocsFetchedAt", older.map(copy -> copy.fetchedAt().toString()).orElse(null));
        model.addAttribute("apiDocsSpecUrl", SPEC_URL);
        model.addAttribute("apiDocsSwaggerUi", SWAGGER_UI);
    }

    /**
     * The keys card's model: none for a visitor. For a member, their own channel's page tells whether they have an OBS
     * widget (then its token, from the Twitchat settings) and whether the custom bindings are open to them; each row
     * is unavailable when its call fails. {@code prefetch}: nothing asked, the card rendered pending.
     */
    private void keys(Model model, boolean prefetch) {
        boolean member = ApiClient.hasSession();
        model.addAttribute("apiDocsMember", member);
        model.addAttribute("apiDocsKeysPending", member && prefetch);
        if (!member || prefetch) return;
        String login = ApiClient.currentLogin();
        ChannelPageData own = login == null ? null : orNull("channel page",
                () -> apiService.channelPage(login, 0, null, null));
        boolean owner = own != null && own.isOwner();
        // Only when there is something to put the widget's token in: OBS (as on the Integrations tab).
        boolean widget = owner && own.obs() != null;
        TwitchatSettingsResponse twitchat = widget ? orNull("Twitchat settings",
                () -> apiService.twitchatSettings(login)) : null;
        // The token is a capability: it goes in the card only, never in a log.
        String token = twitchat == null ? null : twitchat.widgetToken();
        model.addAttribute("widgetToken", token);
        model.addAttribute("widgetUnavailable", own == null || (widget && token == null));
        var custom = owner ? own.customBindings() : null;
        model.addAttribute("customBindingsEnabled", custom != null);
        model.addAttribute("customBindingsExample", custom == null || custom.bindings() == null
                || custom.bindings().isEmpty() ? EXAMPLE_BINDING : custom.bindings().getFirst().name());
    }

    /** {@code call}'s answer, or null when it fails: the docs page renders without it. */
    private static <T> T orNull(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("API docs keys card: {} unavailable ({})", what, e.getClass().getSimpleName());
            return null;
        }
    }
}
