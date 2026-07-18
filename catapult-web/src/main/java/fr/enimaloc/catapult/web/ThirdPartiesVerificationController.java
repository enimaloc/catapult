package fr.enimaloc.catapult.web;

import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Controller
public class ThirdPartiesVerificationController {

    private static final ResponseEntity<String> NOT_FOUND =
            ResponseEntity.notFound().build();

    private final Environment environment;

    public ThirdPartiesVerificationController(Environment environment) {
        this.environment = environment;
    }

    /**
     * Permet de servir des fichiers statiques depuis un dossier configuré.
     */
    @GetMapping("/{file}")
    public ResponseEntity<String> rawWellKnown(@PathVariable String file) {
        return getProperty("catapult.3rd-parties-verification-root-domain.raw")
                .map(Path::of)
                .map(path -> path.resolve(file).normalize())
                .flatMap(this::readFile)
                .map(ResponseEntity::ok)
                .orElse(NOT_FOUND);
    }

    /**
     * Riot code verification
     */
    @GetMapping(value = "/riot.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> microsoftWellKnown() {
        return getProperty(
                "catapult.3rd-parties-verification-root-domain.riot"
        )
                .map(body -> ResponseEntity.ok()
                        .body(body))
                .orElse(NOT_FOUND);
    }

    /**
     * Lecture des fichiers raw.
     */
    private Optional<String> readFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }

            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Récupération d'une propriété non vide.
     */
    private Optional<String> getProperty(String name) {
        return Optional.ofNullable(environment.getProperty(name))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}