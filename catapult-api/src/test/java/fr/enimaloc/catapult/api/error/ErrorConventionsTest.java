package fr.enimaloc.catapult.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * Error conventions, read from catapult-api's sources as text: an error's {@code detail} is English, and an internal
 * route never answers an error without a body (only the frozen controllers keep their empty 404s).
 */
class ErrorConventionsTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern ERROR_CONSTRUCTION = Pattern.compile("new (ResponseStatusException|ApiProblem)\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FRENCH = Pattern.compile(
            "[À-ÿ]|\\b(requis|inconnu|inconnue|indisponible|introuvable|invalide|refusé)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BARE_ERROR =
            Pattern.compile("ResponseEntity\\.notFound\\(\\)|ResponseEntity\\.status\\(HttpStatus\\.(\\w+)\\)");
    /** The frozen routes' controllers: their empty error answers are part of the frozen contract. */
    private static final Set<String> FROZEN_CONTROLLERS =
            Set.of("UserApiV1Controller.java", "UserApiV2Controller.java", "ApiTwitchatWidgetController.java");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"Utilisateur inconnu\"",
                "\"Feature Minecraft indisponible\"",
                "\"Level Xbox invalide: \"",
                "\"Device-code Microsoft refusé\"",
                "\"Reconnecte ton compte, la chaîne est en pause\""
            })
    void frenchTextIsCaught(String line) {
        assertThat(frenchLiterals(line)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"Unknown user\"", "\"Feature not enabled\"", "\"Invalid Xbox level: \" + level"})
    void englishTextPasses(String line) {
        assertThat(frenchLiterals(line)).isEmpty();
    }

    @Test
    void noFrenchDetail() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!ERROR_CONSTRUCTION.matcher(lines.get(i)).find()) {
                    continue;
                }
                List<String> french = new ArrayList<>(frenchLiterals(lines.get(i)));
                if (i + 1 < lines.size()) {
                    french.addAll(frenchLiterals(lines.get(i + 1)));
                }
                if (!french.isEmpty()) {
                    violations.add(location(file, i) + " " + french);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void bareErrorAnswersOnlyOnFrozenControllers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            if (FROZEN_CONTROLLERS.contains(file.getFileName().toString())) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = BARE_ERROR.matcher(lines.get(i));
                while (matcher.find()) {
                    String status = matcher.group(1);
                    if (status == null || HttpStatus.valueOf(status).isError()) {
                        violations.add(location(file, i) + " " + matcher.group());
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    private static List<String> frenchLiterals(String line) {
        List<String> french = new ArrayList<>();
        Matcher literal = STRING_LITERAL.matcher(line);
        while (literal.find()) {
            if (FRENCH.matcher(literal.group(1)).find()) {
                french.add(literal.group());
            }
        }
        return french;
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            List<Path> java = files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertThat(java).as("catapult-api sources under " + SOURCES.toAbsolutePath()).isNotEmpty();
            return java;
        }
    }

    private static String location(Path file, int index) {
        return SOURCES.relativize(file) + ":" + (index + 1);
    }
}
