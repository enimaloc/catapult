package fr.enimaloc.catapult.apidocs;

import fr.enimaloc.catapult.relay.ApiRelayController;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Try it out" on /docs/api calls the documented paths on the site itself (servers rewritten to "/"): each must
 * be one ApiRelayController relays to catapult-api. Read from catapult-api's frozen snapshot of its spec.
 */
class RelayCoversTheDocumentedPathsTest {
    static final Path FROZEN_SPEC = Path.of("../catapult-api/src/test/resources/frozen-routes/api-docs.json");
    static final AntPathMatcher MATCHER = new AntPathMatcher();

    static List<String> documentedPaths() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode snapshot = json.readTree(Files.readString(FROZEN_SPEC));
        JsonNode spec = json.readTree(snapshot.get("body").asString());
        List<String> paths = new ArrayList<>();
        spec.get("paths").propertyNames().forEach(paths::add);
        return paths;
    }

    @Test
    void everyDocumentedPathIsRelayed() throws IOException {
        List<String> paths = documentedPaths();

        assertThat(paths).isNotEmpty().allSatisfy(path -> {
            String concrete = path.replaceAll("\\{[^/}]+}", "x1");
            assertThat(Arrays.stream(ApiRelayController.PATHS).anyMatch(pattern -> MATCHER.match(pattern, concrete)))
                    .as("%s is relayed to catapult-api", path).isTrue();
        });
    }

    @Test
    void noDocumentedPathIsTheSitesOwnHealth() throws IOException {
        assertThat(documentedPaths()).doesNotContain("/api/health");
    }
}
