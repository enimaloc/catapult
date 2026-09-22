package fr.enimaloc.catapult.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ModelFiller {
    private final Optional<BuildProperties> buildProperties;

    public void defaultAttr(Model model, String page) {
        model.addAttribute("features", IndexController.FEATURES);
        model.addAttribute("platforms", IndexController.PLATFORMS);
        model.addAttribute("page", page == null ? "" : page);
        model.addAttribute("spa", IndexController.SPA);
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

    public void fill(Model model, String page, Locale locale) throws IOException {
        switch (page) {
            case "privacy" -> privacy(model, locale);
        }
        defaultAttr(model, page);
    }
}
