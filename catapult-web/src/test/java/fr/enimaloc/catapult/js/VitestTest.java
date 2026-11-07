package fr.enimaloc.catapult.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the browser scripts' Vitest suite (src/test/js) and reports each Vitest test as a JUnit
 * test: one container per test file, one per {@code describe}, one dynamic test per {@code it}.
 * A failing Vitest test fails its JUnit test with Vitest's message; a skipped or todo one is
 * aborted (shown as skipped). A file that fails before running any test (syntax error, failing
 * import) gets a single failing test of its own.
 */
class VitestTest {

    private static final Path PROJECT = Path.of("").toAbsolutePath();
    private static final Path VITEST = PROJECT.resolve("node_modules/vitest/vitest.mjs");
    private static final Path REPORT = PROJECT.resolve("build/vitest/results.json");
    private static final long TIMEOUT_MINUTES = 5;

    @TestFactory
    Stream<DynamicNode> vitest() throws IOException, InterruptedException {
        if (!Files.isRegularFile(VITEST)) {
            fail("Vitest is not installed: run `npm ci` in " + PROJECT);
        }
        Path node = node().orElseThrow(() -> new AssertionError(
                "No node executable on the PATH nor under ~/.nvm/versions/node"));

        Files.createDirectories(REPORT.getParent());
        Files.deleteIfExists(REPORT);
        Path log = REPORT.resolveSibling("vitest.log");
        Process process = new ProcessBuilder(node.toString(), VITEST.toString(), "run",
                "--reporter=json", "--outputFile=" + REPORT)
                .directory(PROJECT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("Vitest did not finish within " + TIMEOUT_MINUTES + " minutes, see " + log);
        }
        if (!Files.isRegularFile(REPORT)) {
            fail("Vitest wrote no report (exit " + process.exitValue() + "):\n" + Files.readString(log));
        }

        JsonNode report = JsonMapper.builder().build().readTree(REPORT.toFile());
        List<DynamicNode> files = new ArrayList<>();
        for (JsonNode file : report.path("testResults")) {
            files.add(file(file));
        }
        return files.stream();
    }

    private static DynamicNode file(JsonNode file) {
        Path path = Path.of(file.path("name").asString());
        URI source = path.toUri();
        String name = PROJECT.relativize(path).toString();

        JsonNode assertions = file.path("assertionResults");
        if (assertions.isEmpty()) {
            String message = file.path("message").asString();
            return DynamicTest.dynamicTest(name, source, () -> {
                if ("failed".equals(file.path("status").asString())) {
                    fail(message.isBlank() ? "The file failed without running any test" : message);
                }
                Assumptions.abort("No test in this file");
            });
        }

        Group root = new Group();
        for (JsonNode test : assertions) {
            Group group = root;
            for (JsonNode title : test.path("ancestorTitles")) {
                group = group.children.computeIfAbsent(title.asString(), t -> new Group());
            }
            group.tests.add(test(test, source));
        }
        return DynamicContainer.dynamicContainer(name, source, root.nodes().stream());
    }

    private static DynamicTest test(JsonNode test, URI source) {
        String status = test.path("status").asString();
        List<String> failures = new ArrayList<>();
        test.path("failureMessages").forEach(m -> failures.add(m.asString()));
        return DynamicTest.dynamicTest(test.path("title").asString(), source, () -> {
            switch (status) {
                case "passed" -> { }
                case "failed" -> fail(String.join("\n\n", failures));
                default -> Assumptions.abort("Vitest status: " + status);
            }
        });
    }

    /** The {@code node} on the PATH, else the newest nvm install (IDEs started outside a shell lack nvm's PATH). */
    private static Optional<Path> node() throws IOException {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = Path.of(dir, "node");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        Path nvm = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
        if (!Files.isDirectory(nvm)) {
            return Optional.empty();
        }
        try (Stream<Path> versions = Files.list(nvm)) {
            return versions.map(v -> v.resolve("bin/node"))
                    .filter(Files::isExecutable)
                    .max(Comparator.comparing(VitestTest::version));
        }
    }

    /** "v22.23.2" → comparable key, so v22 sorts after v9. */
    private static String version(Path nodeBinary) {
        StringBuilder key = new StringBuilder();
        for (String part : nodeBinary.getParent().getParent().getFileName().toString().replace("v", "").split("\\.")) {
            key.append("%06d".formatted(Integer.parseInt(part.replaceAll("\\D", "0"))));
        }
        return key.toString();
    }

    /** One {@code describe} level: its tests in order, then its nested blocks. */
    private static final class Group {
        final Map<String, Group> children = new LinkedHashMap<>();
        final List<DynamicNode> tests = new ArrayList<>();

        List<DynamicNode> nodes() {
            List<DynamicNode> nodes = new ArrayList<>(tests);
            children.forEach((title, child) -> nodes.add(DynamicContainer.dynamicContainer(title, child.nodes())));
            return nodes;
        }
    }
}
