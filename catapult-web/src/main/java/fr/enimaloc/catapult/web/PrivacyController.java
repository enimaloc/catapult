package fr.enimaloc.catapult.web;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@RequiredArgsConstructor
@Controller
public class PrivacyController {

    private final Optional<BuildProperties> buildProperties;

    @GetMapping("/privacy")
    public String privacy(Locale locale, Model model) throws IOException {
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

        return "privacy";
    }
}
