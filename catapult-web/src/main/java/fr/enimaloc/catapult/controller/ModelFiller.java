package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ModelFiller {
    private final Optional<BuildProperties> buildProperties;
    private final MessageSource messageSource;
    private final ApiService apiService;

    public void defaultAttr(Model model, String page) {
        model.addAttribute("features", IndexController.FEATURES);
        model.addAttribute("platforms", IndexController.PLATFORMS);
        model.addAttribute("page", page == null ? "" : page);
        model.addAttribute("spa", IndexController.SPA);
    }

    public void defaultAttr(Model model, String page, Locale locale) {
        defaultAttr(model, page);
        model.addAttribute("titles", titles(locale));
    }

    private Map<String, String> titles(Locale locale) {
        return IndexController.SPA.stream().collect(Collectors.toMap(
                IndexController.SPAPage::getId,
                spaPage -> messageSource.getMessage(spaPage.getTitleKey(), null, spaPage.getTitleKey(), locale)
        ));
    }

    public void privacy(Model model, Locale locale) throws IOException {
        Resource privacyResource = new ClassPathResource(
                "lang/privacy/%s.html".formatted(locale.getLanguage())
        );

        String lang = locale.getLanguage();
        if (!privacyResource.exists()) {
            privacyResource = new ClassPathResource("lang/privacy/en.html");
            lang = "en";
        }

        String lastModifiedKey = "privacy.%s.last-update".formatted(lang);
        String lastModified = buildProperties
                .map(p -> p.get(lastModifiedKey))
                .orElse(null);

        model.addAttribute("privacy", new String(privacyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        model.addAttribute("lastUpdate", lastModified != null ? Instant.parse(lastModified) : Instant.EPOCH);
    }

    public void fill(Model model, String page, Locale locale, HttpServletRequest request) throws IOException {
        switch (page) {
            case "privacy" -> privacy(model, locale);
            case "channels" -> channels(model);
        }
        defaultAttr(model, page, locale);
    }

    public void channels(Model model) {
        model.addAttribute("channels", apiService.channelList().channels());
    }

    public void channel(Model model, String username, int page, String status, String source) {
        model.addAttribute("username", username);
        model.addAttribute("channelPage", apiService.channelPage(username, page, status, source));
    }

    public void error(Model model, HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        error(model, status instanceof Integer ? (Integer) status : 500);
    }

    public void error(Model model, int code) {
        model.addAttribute("errorCode", code);

        String prefix = switch (code) {
            case 400 -> "error.400";
            case 401 -> "error.401";
            case 403 -> "error.403";
            case 404 -> "error.404";
            case 405 -> "error.405";
            case 429 -> "error.429";
            case 500 -> "error.500";
            case 502 -> "error.502";
            case 503 -> "error.503";
            default -> "error.generic";
        };

        model.addAttribute("errorEyebrow", "error.eyebrow");
        model.addAttribute("errorTitle", prefix + ".title");
        model.addAttribute("errorDescription", prefix + ".description");
    }
}
