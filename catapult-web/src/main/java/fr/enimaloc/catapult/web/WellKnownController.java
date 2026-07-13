package fr.enimaloc.catapult.web;

import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping("/.well-known")
public class WellKnownController {

    private static final String MICROSOFT_IDENTITY_ASSOCIATION =
            "{\"associatedApplications\": [{\"applicationId\": \"%s\"}]}";

    private static final ResponseEntity<String> NOT_FOUND =
            ResponseEntity.notFound().build();

    private final Environment environment;

    public WellKnownController(Environment environment) {
        this.environment = environment;
    }

    /**
     * Permet de servir des fichiers statiques depuis un dossier configuré.
     */
    @GetMapping("/{file}")
    public ResponseEntity<String> rawWellKnown(@PathVariable String file) {
        return getProperty("catapult.well-known.raw")
                .map(Path::of)
                .map(path -> path.resolve(file).normalize())
                .flatMap(this::readFile)
                .map(ResponseEntity::ok)
                .orElse(NOT_FOUND);
    }

    /**
     * Microsoft identity association
     */
    @GetMapping("/microsoft-identity-association")
    public ResponseEntity<String> microsoftWellKnown() {
        return getProperty(
                "catapult.well-known.microsoft-identity-association.application-id"
        )
                .map(MICROSOFT_IDENTITY_ASSOCIATION::formatted)
                .map(body -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body))
                .orElse(NOT_FOUND);
    }

    /**
     * Security.txt RFC 9116
     */
    @GetMapping(value = "/security.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> securityWellKnown() {
        return getProperty("catapult.well-known.security.contact")
                .map(contact -> {
                    StringBuilder security = new StringBuilder();

                    security.append("Contact: ")
                            .append(contact)
                            .append("\n");

                    getProperty("catapult.well-known.security.expires")
                            .ifPresent(expires -> security.append("Expires: ")
                                    .append(expires)
                                    .append("\n"));

                    getProperty("catapult.well-known.security.encryption")
                            .ifPresent(encryption -> security.append("Encryption: ")
                                    .append(encryption)
                                    .append("\n"));

                    getProperty("catapult.well-known.security.acknowledgments")
                            .ifPresent(ack -> security.append("Acknowledgments: ")
                                    .append(ack)
                                    .append("\n"));

                    getProperty("catapult.well-known.security.preferred-languages")
                            .ifPresent(languages -> security.append("Preferred-Languages: ")
                                    .append(languages)
                                    .append("\n"));

                    getProperty("catapult.well-known.security.policy")
                            .ifPresent(policy -> security.append("Policy: ")
                                    .append(policy)
                                    .append("\n"));

                    getProperty("catapult.well-known.security.canonical")
                            .ifPresent(canonical -> security.append("Canonical: ")
                                    .append(canonical)
                                    .append("\n"));

                    return security.toString();
                })
                .map(ResponseEntity::ok)
                .orElse(NOT_FOUND);
    }

    /**
     * ACME HTTP-01 challenge
     *
     * Exemple:
     * /.well-known/acme-challenge/token123
     */
    @GetMapping(value = "/acme-challenge/{token}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> acmeWellKnown(@PathVariable String token) {
        return getAcmeChallenges()
                .map(challenges -> challenges.get(token))
                .map(ResponseEntity::ok)
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
     * Récupération des challenges ACME depuis les properties.
     */
    private Optional<Map<String, String>> getAcmeChallenges() {
        return Optional.of(environment.getProperty("catapult.well-known.acme.challenges"))
                .map(value -> Map.of());
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