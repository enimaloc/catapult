package fr.enimaloc.catapult.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An external route's answer as recorded before the routes were reorganised: status, the headers that matter and the
 * raw body. The files under {@code src/test/resources/frozen-routes/} are written once (with
 * {@code FROZEN_ROUTES_RECORD=1}) and never edited again: a later change that alters one of these answers fails here.
 */
final class FrozenSnapshot {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String VOLATILE = "<volatile>";
    private static final List<String> HEADERS =
            List.of("Content-Type", "Location", "Allow", "WWW-Authenticate", "Cache-Control", "Vary");

    private FrozenSnapshot() {}

    /**
     * Pins {@code response} against the snapshot {@code name}. The members of the JSON body at the {@code
     * volatilePointers} (RFC 6901) change from run to run (timestamps, generated ids): both bodies are compared with
     * those members masked; with none the raw bodies must be equal.
     */
    static void assertMatches(String name, HttpResponse<String> response, String... volatilePointers) {
        ObjectNode actual = capture(response);
        if ("1".equals(System.getenv("FROZEN_ROUTES_RECORD"))) {
            record(name, actual);
            return;
        }
        JsonNode expected = read(name);

        assertThat(actual.get("status")).as("%s status", name).isEqualTo(expected.get("status"));
        assertThat(actual.get("headers")).as("%s headers", name).isEqualTo(expected.get("headers"));
        String actualBody = actual.get("body").asString();
        String expectedBody = expected.get("body").asString();
        if (volatilePointers.length == 0) {
            assertThat(actualBody).as("%s body", name).isEqualTo(expectedBody);
        } else {
            assertThat(mask(actualBody, volatilePointers))
                    .as("%s body, volatile members masked", name)
                    .isEqualTo(mask(expectedBody, volatilePointers));
        }
    }

    private static ObjectNode capture(HttpResponse<String> response) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("status", response.statusCode());
        Map<String, List<String>> kept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((header, values) -> {
            if (HEADERS.stream().anyMatch(header::equalsIgnoreCase)
                    || header.toLowerCase(Locale.ROOT).startsWith("access-control-")) {
                // the port of this run's server, in the origin a header names
                kept.put(header, values.stream().map(v -> v.replaceAll("localhost:\\d+", "localhost:<port>")).toList());
            }
        });
        ObjectNode headers = snapshot.putObject("headers");
        List<String> ordered = new ArrayList<>(kept.keySet());
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        for (String header : ordered) {
            var values = headers.putArray(canonical(header));
            kept.get(header).forEach(values::add);
        }
        snapshot.put("body", response.body());
        return snapshot;
    }

    private static String canonical(String header) {
        StringBuilder out = new StringBuilder();
        for (String part : header.toLowerCase(Locale.ROOT).split("-")) {
            if (!out.isEmpty()) {
                out.append('-');
            }
            out.append(part.equals("www") ? "WWW" : Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return out.toString();
    }

    private static String mask(String body, String[] pointers) {
        JsonNode tree = JSON.readTree(body);
        for (String pointer : pointers) {
            int cut = pointer.lastIndexOf('/');
            JsonNode parent = tree.at(pointer.substring(0, cut));
            String member = pointer.substring(cut + 1);
            if (parent instanceof ObjectNode object && object.has(member)) {
                object.put(member, VOLATILE);
            }
        }
        return JSON.writeValueAsString(tree);
    }

    private static JsonNode read(String name) {
        try (InputStream in = FrozenSnapshot.class.getResourceAsStream("/frozen-routes/" + name + ".json")) {
            assertThat(in)
                    .as("snapshot %s (record it with FROZEN_ROUTES_RECORD=1)", name)
                    .isNotNull();
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void record(String name, ObjectNode snapshot) {
        try {
            Path file = Path.of("src/test/resources/frozen-routes", name + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
