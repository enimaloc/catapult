package fr.enimaloc.catapult.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThirdPartiesVerificationControllerTest {

    @Mock private Environment environment;

    private ThirdPartiesVerificationController controller;

    @TempDir Path tempDir;
    private Path root;
    private Path secretOutsideRoot;

    @BeforeEach
    void setup() throws IOException {
        controller = new ThirdPartiesVerificationController(environment);

        root = tempDir.resolve("well-known-root");
        Files.createDirectory(root);
        Files.writeString(root.resolve("apple-app-site-association"), "verification-content");

        secretOutsideRoot = tempDir.resolve("secret.txt");
        Files.writeString(secretOutsideRoot, "should never be readable via /{file}");

        doReturn(root.toString()).when(environment)
                .getProperty("catapult.3rd-parties-verification-root-domain.raw");
    }

    @Test
    void rawWellKnown_servesFileUnderConfiguredRoot() {
        ResponseEntity<String> response = controller.rawWellKnown("apple-app-site-association");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("verification-content");
    }

    @Test
    void rawWellKnown_rejectsDotDotSegment() {
        // ".." seul reste un unique segment de chemin valide côté Spring
        // (pas de "/"), mais ne doit jamais échapper à root.
        ResponseEntity<String> response = controller.rawWellKnown("..");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void rawWellKnown_rejectsFileNameContainingSlash() {
        ResponseEntity<String> response = controller.rawWellKnown("../secret.txt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rawWellKnown_rejectsFileNameContainingBackslash() {
        ResponseEntity<String> response = controller.rawWellKnown("..\\secret.txt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rawWellKnown_notFoundForMissingFile() {
        ResponseEntity<String> response = controller.rawWellKnown("does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rawWellKnown_notFoundWhenRootPropertyUnset() {
        doReturn(null).when(environment).getProperty("catapult.3rd-parties-verification-root-domain.raw");

        ResponseEntity<String> response = controller.rawWellKnown("apple-app-site-association");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void riotTxt_servesConfiguredVerificationBody() {
        doReturn("riot-verification-code").when(environment)
                .getProperty("catapult.3rd-parties-verification-root-domain.riot");

        ResponseEntity<String> response = controller.microsoftWellKnown();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("riot-verification-code");
    }

    @Test
    void riotTxt_notFoundWhenPropertyUnset() {
        doReturn(null).when(environment).getProperty("catapult.3rd-parties-verification-root-domain.riot");

        ResponseEntity<String> response = controller.microsoftWellKnown();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}