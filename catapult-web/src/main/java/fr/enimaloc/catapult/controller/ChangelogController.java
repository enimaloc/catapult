package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.changelog.ChangelogSection;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The public changelog page, full ({@code /changelog}) and as spa.js's fragment ({@code /spa/changelog}):
 * catapult-api's history of versions, asked without the session's bearer (it is public) and kept a
 * few minutes, a version only changing with a deployment. {@code changelog} is null when catapult-api
 * can't give it.
 */
@Controller
public class ChangelogController {
    static final Duration TTL = Duration.ofMinutes(5);

    private record Cached(List<ChangelogSection> sections, Instant until) {}

    private final ModelFiller filler;
    private final ApiClient apiClient;
    private final Clock clock;
    private volatile Cached cached;

    @Autowired
    public ChangelogController(ModelFiller filler, ApiClient apiClient) {
        this(filler, apiClient, Clock.systemUTC());
    }

    ChangelogController(ModelFiller filler, ApiClient apiClient, Clock clock) {
        this.filler = filler;
        this.apiClient = apiClient;
        this.clock = clock;
    }

    @GetMapping("/changelog")
    String page(Model model, Locale locale) throws IOException {
        model.addAttribute("error", false);
        model.addAttribute("changelog", sections());
        filler.fill(model, "changelog", locale);
        return "index";
    }

    @GetMapping("/spa/changelog")
    String fragment(Model model) {
        model.addAttribute("changelog", sections());
        filler.defaultAttr(model, "changelog");
        return "pages/changelog :: changelog";
    }

    /** The versions, newest first; null when catapult-api can't give them (that isn't kept). */
    List<ChangelogSection> sections() {
        Instant now = clock.instant();
        Cached current = cached;
        if (current != null && now.isBefore(current.until())) return current.sections();
        ApiClient.Result<ChangelogSection[]> result = apiClient.getAnonymous("/api/changelog", ChangelogSection[].class);
        if (!result.ok() || result.body() == null) return null;
        List<ChangelogSection> sections = List.of(result.body());
        cached = new Cached(sections, now.plus(TTL));
        return sections;
    }
}
