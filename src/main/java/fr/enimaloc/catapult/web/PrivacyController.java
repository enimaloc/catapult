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

@RequiredArgsConstructor
@Controller
public class PrivacyController {

    private final BuildProperties buildProperties;

    @GetMapping("/privacy")
    public String privacy(Locale locale, Model model) throws IOException {
        Resource privacyResource = new ClassPathResource(
            "lang/privacy/%s.html".formatted(locale.getLanguage())
        );
        String lastModified = buildProperties.get("privacy.%s.last-update".formatted(locale.getLanguage()));

        if (!privacyResource.exists()) {
            privacyResource = new ClassPathResource("lang/privacy/en.html");
            lastModified = buildProperties.get("privacy.en.last-update");
        }

        model.addAttribute("privacy", new String(privacyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        model.addAttribute("lastUpdate", Instant.parse(Objects.requireNonNull(lastModified)));

        return "privacy";
    }
}
