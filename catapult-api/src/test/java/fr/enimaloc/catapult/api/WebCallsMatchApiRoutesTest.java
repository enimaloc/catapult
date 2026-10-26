package fr.enimaloc.catapult.api;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.server.PathContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Every path catapult-web sends to catapult-api is a route catapult-api serves, with the same method: a path or verb
 * left behind answers 404 or 405, which only the browser would show. The calls are read from catapult-web's sources,
 * where each one is a string literal ({@code client.get("/api/…", …)}, {@code client.call(HttpMethod.PATCH, "/api/…",
 * …)}, the {@code StringBuilder} a query is appended to).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.config.additional-location=file:./src/main/resources/")
// The optional integrations on, as in the access matrix, so their routes are mapped too
@TestPropertySource(
        locations = "classpath:full-context.properties",
        properties = {
            "xbox.enabled=true",
            "xbox.client-id=full-context-test",
            "xbox.client-secret=full-context-test",
            "minecraft.enabled=true",
            "minecraft.msa-client-id=full-context-test",
            "dtdd.enabled=true"
        })
@Import(FullContextContainers.class)
@DirtiesContext
class WebCallsMatchApiRoutesTest {

    private static final Path WEB = Path.of("../catapult-web/src/main/java/fr/enimaloc/catapult/");
    private static final List<String> FILES = List.of(
            "service/real/RealApiService.java",
            "service/http/ApiClient.java",
            "twitchat/ApiNotificationFlusher.java",
            "service/real/ChannelIds.java");
    /** {@code ApiClient}'s own literal: the session check it does not warn about, not a call. */
    private static final String AUTH_VALIDATE_PATH = "/api/auth/validate";

    private static final String LITERAL = "\\s*\"(/[^\"]*)\"";
    private static final Pattern GET = Pattern.compile("\\.(?:get|getAnonymous|getLocalized)\\(" + LITERAL);
    private static final Pattern POST = Pattern.compile("\\.(?:post|postVoid)\\(" + LITERAL);
    private static final Pattern PUT = Pattern.compile("\\.putVoid\\(" + LITERAL);
    private static final Pattern PATCH = Pattern.compile("\\.patchVoid\\(" + LITERAL);
    private static final Pattern DELETE = Pattern.compile("\\.delete\\(" + LITERAL);
    private static final Pattern CALL = Pattern.compile("\\.(?:call|callAs)\\(\\s*HttpMethod\\.(\\w+)\\s*," + LITERAL);
    private static final Pattern BUILDER = Pattern.compile("new StringBuilder\\(" + LITERAL);
    private static final Pattern API_LITERAL = Pattern.compile("\"(/api/[^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]*}");

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyWebCallHitsARoute() throws IOException {
        PathPatternParser parser = new PathPatternParser();
        List<String> unmatched = new ArrayList<>();
        for (Call call : calls()) {
            PathContainer path = PathContainer.parsePath(VARIABLE.matcher(call.path()).replaceAll("x1"));
            boolean served = false;
            for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
                boolean method = info.getMethodsCondition().getMethods().isEmpty()
                        || info.getMethodsCondition().getMethods().stream().anyMatch(m -> m.name().equals(call.method()));
                if (method && info.getPatternValues().stream().anyMatch(p -> parser.parse(p).matches(path))) {
                    served = true;
                    break;
                }
            }
            if (!served) {
                unmatched.add(call.method() + " " + call.path() + " (" + call.where() + ")");
            }
        }
        unmatched.sort(null);
        assertThat(unmatched).as("catapult-web calls no catapult-api route serves").isEmpty();
    }

    @Test
    void everyApiLiteralIsACall() throws IOException {
        List<String> strays = new ArrayList<>();
        for (String file : FILES) {
            String source = source(file);
            if (source == null) {
                continue;
            }
            Set<Integer> calls = new HashSet<>();
            for (Call call : calls(file, source)) {
                calls.add(call.offset());
            }
            Matcher literal = API_LITERAL.matcher(source);
            while (literal.find()) {
                boolean authCheck = file.endsWith("ApiClient.java") && literal.group(1).equals(AUTH_VALIDATE_PATH);
                if (!calls.contains(literal.start(1)) && !authCheck) {
                    strays.add(literal.group(1) + " (" + file + ":" + line(source, literal.start()) + ")");
                }
            }
        }
        assertThat(strays).as("catapult-api paths catapult-web builds outside a call this test reads").isEmpty();
    }

    @Test
    void parserStillSeesTheCalls() throws IOException {
        assertThat(calls().size()).as("calls found").isGreaterThanOrEqualTo(180);
    }

    private static List<Call> calls() throws IOException {
        List<Call> calls = new ArrayList<>();
        for (String file : FILES) {
            String source = source(file);
            if (source != null) {
                calls.addAll(calls(file, source));
            }
        }
        return calls;
    }

    private static List<Call> calls(String file, String source) {
        List<Call> calls = new ArrayList<>();
        find(calls, file, source, GET, "GET");
        find(calls, file, source, POST, "POST");
        find(calls, file, source, PUT, "PUT");
        find(calls, file, source, PATCH, "PATCH");
        find(calls, file, source, DELETE, "DELETE");
        find(calls, file, source, BUILDER, "GET");
        Matcher matcher = CALL.matcher(source);
        while (matcher.find()) {
            calls.add(call(file, source, matcher.group(1), matcher, 2));
        }
        return calls;
    }

    private static void find(List<Call> calls, String file, String source, Pattern pattern, String method) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            calls.add(call(file, source, method, matcher, 1));
        }
    }

    private static Call call(String file, String source, String method, Matcher matcher, int group) {
        String literal = matcher.group(group);
        int query = literal.indexOf('?');
        String path = query < 0 ? literal : literal.substring(0, query);
        return new Call(method, path, file + ":" + line(source, matcher.start(group)), matcher.start(group));
    }

    /** {@code file}'s source, or {@code null} while it does not exist ({@code ChannelIds} comes with its slice). */
    private static String source(String file) throws IOException {
        Path path = WEB.resolve(file);
        if (!Files.exists(path)) {
            assertThat(file).as("only ChannelIds may be missing").isEqualTo("service/real/ChannelIds.java");
            return null;
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int line(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** One call: its method, the path it sends (up to its query), where it is, and its literal's offset. */
    private record Call(String method, String path, String where, int offset) {}
}
