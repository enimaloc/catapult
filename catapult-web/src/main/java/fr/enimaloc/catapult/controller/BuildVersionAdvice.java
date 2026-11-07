package fr.enimaloc.catapult.controller;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Optional;

/**
 * The version catapult-web was built as, for the footer of every page ({@code ${appVersion}}):
 * the build info's version, null when the build wrote none (e.g. run from the IDE).
 */
@ControllerAdvice
public class BuildVersionAdvice {
    private final String version;

    public BuildVersionAdvice(Optional<BuildProperties> buildProperties) {
        this.version = buildProperties.map(BuildProperties::getVersion).filter(v -> !v.isBlank()).orElse(null);
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return version;
    }
}
