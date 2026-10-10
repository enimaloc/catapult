package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.FeatureDto;
import fr.enimaloc.catapult.dto.index.PlatformDto;
import fr.enimaloc.catapult.dto.index.SpaPage;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Fills the model every page template needs, shared by the full-page and fragment routes. */
@Component
@RequiredArgsConstructor
public class ModelFiller {
    /** Error codes with their own error.<code>.* messages; anything else uses error.generic.*. */
    private static final Set<Integer> DESCRIBED_ERRORS = Set.of(400, 401, 403, 404, 405, 429, 500, 502, 503);
    private static final String DEFAULT_PRIVACY_LANGUAGE = "en";

    /** Static, page-independent data every template can read as ${app.*}. */
    public record AppModel(List<FeatureDto> features, List<PlatformDto> platforms, List<SpaPage> spa) {}

    private static final AppModel APP_MODEL =
            new AppModel(SiteCatalog.FEATURES, SiteCatalog.PLATFORMS, SiteCatalog.PAGES);

    private final Optional<BuildProperties> buildProperties;
    private final MessageSource messageSource;
    private final ApiService apiService;

    public void defaultAttr(Model model, String page) {
        model.addAttribute("app", APP_MODEL);
        model.addAttribute("page", page == null ? "" : page);
    }

    /** Full-page variant: also the localized titles spa.js swaps in on client-side navigation. */
    public void defaultAttr(Model model, String page, Locale locale) {
        defaultAttr(model, page);
        model.addAttribute("titles", titles(locale));
    }

    private Map<String, String> titles(Locale locale) {
        return SiteCatalog.PAGES.stream().collect(Collectors.toMap(
                SpaPage::getId,
                page -> messageSource.getMessage(page.getTitleKey(), null, page.getTitleKey(), locale)));
    }

    /** The page-specific model of a static page, then the shared one. */
    public void fill(Model model, String page, Locale locale) throws IOException {
        switch (page) {
            case "privacy" -> privacy(model, locale);
            case "channels" -> channels(model);
            default -> { }
        }
        defaultAttr(model, page, locale);
    }

    /**
     * The privacy policy in the visitor's language (English when it has no translation), and
     * when that file last changed, as recorded in the build info (epoch when unknown).
     */
    public void privacy(Model model, Locale locale) throws IOException {
        String language = locale.getLanguage();
        Resource policy = privacyPolicy(language);
        if (!policy.exists()) {
            language = DEFAULT_PRIVACY_LANGUAGE;
            policy = privacyPolicy(language);
        }

        String lastUpdateKey = "privacy.%s.last-update".formatted(language);
        Instant lastUpdate = buildProperties
                .map(properties -> properties.get(lastUpdateKey))
                .map(Instant::parse)
                .orElse(Instant.EPOCH);

        model.addAttribute("privacy", new String(policy.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        model.addAttribute("lastUpdate", lastUpdate);
    }

    private static Resource privacyPolicy(String language) {
        return new ClassPathResource("lang/privacy/%s.html".formatted(language));
    }

    public void channels(Model model) {
        model.addAttribute("channels", apiService.channelList().channels());
    }

    /** The dashboard model; settings and the DTDD mapping only for the channel's owner. */
    public void channel(Model model, String username, int page, String status, String source) {
        model.addAttribute("username", username);
        var channelPage = apiService.channelPage(username, page, status, source);
        model.addAttribute("channelPage", channelPage);
        if (channelPage.isOwner()) {
            model.addAttribute("channelSettings", apiService.channelSettings(username));
            model.addAttribute("dtddMapping", apiService.dtddMappingStatus(username));
        }
    }

    /** Error page model for the status the servlet container forwarded (500 when absent). */
    public void error(Model model, HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        error(model, status instanceof Integer code ? code : HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    public void error(Model model, int code) {
        String prefix = DESCRIBED_ERRORS.contains(code) ? "error." + code : "error.generic";

        model.addAttribute("errorCode", code);
        model.addAttribute("errorEyebrow", "error.eyebrow");
        model.addAttribute("errorTitle", prefix + ".title");
        model.addAttribute("errorDescription", prefix + ".description");
    }
}
