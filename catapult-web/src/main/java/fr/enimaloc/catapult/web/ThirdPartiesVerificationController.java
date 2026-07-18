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
import java.util.regex.Pattern;

@Controller
public class ThirdPartiesVerificationController {

    private static final ResponseEntity<String> NOT_FOUND =
            ResponseEntity.notFound().build();

    /**
     * {file} n'est qu'un seul segment de chemin (pas de "/"), mais un
     * segment littéral ".." resterait valide côté routage Spring et
     * remonterait d'un niveau via resolve().normalize() sans ce filtre.
     */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final Environment environment;

    public ThirdPartiesVerificationController(Environment environment) {
        this.environment = environment;
    }

    /**
     * Permet de servir des fichiers statiques depuis un dossier configuré.
     * Exclut explicitement "ws" : le handshake WebSocket sur /ws est
     * enregistré via WebSocketHandlerRegistry (WsConfig), pas comme un
     * @Controller — RequestMappingHandlerMapping capte /ws en premier dans
     * la chaîne de HandlerMapping si on ne l'exclut pas ici, ce qui casse
     * l'upgrade (404 au lieu du handshake).
     */
    @GetMapping("/{file:(?!ws$).+}")
    public ResponseEntity<String> rawWellKnown(@PathVariable String file) {
        if (!SAFE_FILE_NAME.matcher(file).matches()) {
            return NOT_FOUND;
        }
        return getProperty("catapult.3rd-parties-verification-root-domain.raw")
                .flatMap(root -> resolveWithinRoot(root, file))
                .flatMap(this::readFile)
                .map(ResponseEntity::ok)
                .orElse(NOT_FOUND);
    }

    /**
     * Résout {file} sous root et vérifie que le chemin réel obtenu reste
     * bien contenu dans root (défense en profondeur derrière l'allowlist
     * de {@link #SAFE_FILE_NAME} — ceinture et bretelles).
     */
    private Optional<Path> resolveWithinRoot(String root, String file) {
        try {
            Path base = Path.of(root).toRealPath();
            Path target = base.resolve(file).normalize();
            if (!Files.exists(target)) {
                return Optional.empty();
            }
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(base)) {
                return Optional.empty();
            }
            return Optional.of(realTarget);
        } catch (IOException e) {
            return Optional.empty();
        }
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