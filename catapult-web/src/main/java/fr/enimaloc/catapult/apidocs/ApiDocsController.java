package fr.enimaloc.catapult.apidocs;

import fr.enimaloc.catapult.controller.ModelFiller;
import fr.enimaloc.catapult.service.ApiHealthProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The API documentation, on the site: the page ({@code /docs/api}, and spa.js's fragment of it,
 * {@code /spa/docs/api}) runs Swagger UI on the site's copy of catapult-api's spec ({@code /docs/api/openapi.json},
 * see ApiDocsSpec), and says whether catapult-api answers (ApiHealthProbe, followed in the page from
 * {@code /api/health}). A cached copy is shown with the time it was fetched (worded in the visitor's time zone by
 * api-docs.js, from the instant in {@code data-fetched-at}); without any copy the page says so,
 * still a 200. catapult-api's own Swagger UI addresses, which the site no longer relays, lead here.
 */
@Controller
public class ApiDocsController {
    static final String PAGE = "docs/api";
    static final String SPEC_URL = "/docs/api/openapi.json";
    static final String SWAGGER_UI = "/_static/swagger-ui/" + ApiDocsAssets.SWAGGER_UI_VERSION;
    private static final DateTimeFormatter FETCHED_AT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final ModelFiller filler;
    private final ApiDocsSpec spec;
    private final ApiHealthProbe probe;

    @Autowired
    public ApiDocsController(ModelFiller filler, ApiDocsSpec spec, ApiHealthProbe probe) {
        this.filler = filler;
        this.spec = spec;
        this.probe = probe;
    }

    @GetMapping("/docs/api")
    String page(Model model, Locale locale) throws IOException {
        model.addAttribute("error", false);
        docs(model);
        filler.fill(model, PAGE, locale);
        return "index";
    }

    @GetMapping("/spa/docs/api")
    String fragment(Model model) {
        docs(model);
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

    /** catapult-api's own Swagger UI, which links and bookmarks still name. */
    @GetMapping({"/swagger-ui.html", "/swagger-ui/index.html"})
    String swaggerUi() {
        return "redirect:/docs/api";
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
        model.addAttribute("apiUp", probe.up());
    }
}
