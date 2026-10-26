package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.controller.SiteMetaTags;
import fr.enimaloc.catapult.perf.FakeApi;
import fr.enimaloc.catapult.service.debug.DebugMembers;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.service.real.ChannelIds;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * How many calls to catapult-api, and how long, each row of {@value #BUDGETS} takes, on the whole application
 * (real security chain, real sessions in Redis) in front of a {@link FakeApi}. Each row is called once to warm up
 * (its fresh session is validated then, as the first page of a session is), then {@value #CALLS} times: the call
 * count is the last call's, the time the median of the {@value #CALLS}. It is done with {@value FakeApi#SMALL}
 * elements in every collection the pages show, then again with {@value FakeApi#LARGE}.
 *
 * <p>Before every call the caches catapult-web keeps for a while (a channel's id by its login, the meta tags, who
 * is in debug mode) are emptied: a page is counted as it costs once they have expired, not luckily within their
 * TTL, so the count does not depend on how long the run takes.
 *
 * <p>A row fails when its page answers anything but a 200, calls catapult-api more times than its
 * {@code api_calls} budget (the count measured when the row was written: lower one that drops), takes more than
 * {@value #MAX_MILLIS} ms, or calls more at {@value FakeApi#LARGE} elements than at {@value FakeApi#SMALL}: it then
 * asks for its collection one element at a time. A row whose {@code notes} is {@code n+1} is such a page, known and
 * left as it is: only its count at {@value FakeApi#LARGE} is held to its budget. A row whose {@code notes} is
 * {@code fixed:<reason>} (no comma) answers the same at both sizes, for that reason: it shows none of the
 * collections.
 *
 * <p>Callers ({@code caller}): {@code owner} ({@link FakeApi#OWNER_LOGIN}'s channel), {@code visitor} (a member
 * who moderates it) and {@code admin} ({@code ROLE_ADMIN}). {@code {id}} in a path is {@link FakeApi#EXPERIMENT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebPageBudgetTest extends WebFullContextTestBase {

    static final String BUDGETS = "page-budgets.csv";
    static final int CALLS = 5;
    static final long MAX_MILLIS = 1000;
    private static final List<String> CALLERS = List.of("owner", "visitor", "admin");
    private static final List<String> HEADER = List.of("method", "path", "caller", "api_calls", "notes");
    private static final String N_PLUS_ONE = "n+1";
    private static final String FIXED = "fixed:";
    /** SiteMetaTags' topic: its own handler empties the list, ours (registered after it) tells it is done. */
    private static final String META_TAGS_CLEAR = "meta-tags.clear";

    /** Answers from the start: DegradedState asks catapult-api as soon as the context is up. */
    private static final FakeApi FAKE = new FakeApi(JsonMapper.builder().build());
    private static final MockWebServer API = FakeApi.server();

    static {
        try {
            API.setDispatcher(FAKE);
            API.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void api(DynamicPropertyRegistry registry) {
        registry.add("catapult.backend-url", () -> "http://127.0.0.1:" + API.getPort());
    }

    @Autowired
    SessionRepository<? extends Session> sessions;

    @Autowired
    ChannelIds channelIds;

    @Autowired
    DebugMembers debugMembers;

    @Autowired
    SiteMetaTags metaTags;

    @Autowired
    InstanceBus bus;

    private final HttpClient http = HttpClient.newHttpClient();
    private volatile CountDownLatch metaTagsCleared = new CountDownLatch(0);
    private final Map<Row, Measure> small = new HashMap<>();
    private final Map<Row, Measure> large = new HashMap<>();

    /** Measures every row at both sizes up front, so that each row's test only reads its measures. */
    @BeforeAll
    void measure() throws Exception {
        bus.on(META_TAGS_CLEAR, ignored -> metaTagsCleared.countDown());
        List<Row> rows = rows();
        for (Row row : rows) {
            small.put(row, measure(row));
        }
        FAKE.grow();
        for (Row row : rows) {
            large.put(row, measure(row));
        }
        for (Row row : rows) {
            System.out.printf("page budget %s: HTTP %d, %d api calls at %d, %d at %d; %d ms, %d ms; %s%n", row,
                    large.get(row).status(), small.get(row).calls().size(), FakeApi.SMALL,
                    large.get(row).calls().size(), FakeApi.LARGE, small.get(row).millis(), large.get(row).millis(),
                    large.get(row).calls());
        }
    }

    @AfterAll
    void stopApi() throws IOException {
        API.shutdown();
    }

    static List<Row> rows() throws IOException {
        return Row.parse(BUDGETS);
    }

    @Test
    void fakeApiAnswersEveryCall() {
        assertThat(FAKE.unexpected()).as("calls the fake catapult-api has no answer for (each got a 599)").isEmpty();
    }

    /** The count of a page is the same however many times it is shown: no cache left warm by a call before it. */
    @Test
    void countsAreStableAcrossThreeRuns() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (Row row : rows()) {
            String cookie = login(row.caller());
            call(row, cookie);
            List<List<String>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                runs.add(call(row, cookie).calls());
            }
            softly.assertThat(runs).as("%s : the calls of three runs", row.label()).containsOnly(runs.getFirst());
        }
        softly.assertAll();
    }

    /**
     * A row whose page does not move with the fixture does not prove its count holds whatever the size; a row noted
     * {@code fixed} must indeed answer the same at both sizes, so that the note cannot hide one that should move.
     */
    @Test
    void grownFixtureChangesThePage() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        for (Row row : rows()) {
            if (row.fixed()) {
                softly.assertThat(large.get(row).body())
                        .as("%s is noted %s but answers differently with %d elements: drop the note", row,
                                row.notes(), FakeApi.LARGE)
                        .isEqualTo(small.get(row).body());
                continue;
            }
            softly.assertThat(large.get(row).body())
                    .as("%s answers the same with %d elements as with %d: grow what it shows, or note it fixed:<why>",
                            row, FakeApi.LARGE, FakeApi.SMALL)
                    .isNotEqualTo(small.get(row).body());
        }
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void pageStaysWithinBudget(Row row) {
        Measure atSmall = small.get(row);
        Measure atLarge = large.get(row);
        SoftAssertions softly = new SoftAssertions();
        for (Measure measure : List.of(atSmall, atLarge)) {
            softly.assertThat(measure.status())
                    .as("%s answered %d: %s", row.label(), measure.status(), measure.body())
                    .isEqualTo(200);
            softly.assertThat(measure.millis())
                    .as("%s : %d ms, budget %d%n%s", row.label(), measure.millis(), MAX_MILLIS, paths(measure))
                    .isLessThanOrEqualTo(MAX_MILLIS);
        }
        String budget = row.apiCalls() == null ? "none" : String.valueOf(row.apiCalls());
        List<Measure> held = row.nPlusOne() ? List.of(atLarge) : List.of(atSmall, atLarge);
        for (Measure measure : held) {
            softly.assertThat(row.apiCalls() != null && measure.calls().size() <= row.apiCalls())
                    .as("%s : %d api calls, budget %s%n%s", row.label(), measure.calls().size(), budget,
                            paths(measure))
                    .isTrue();
        }
        if (!row.nPlusOne()) {
            softly.assertThat(atLarge.calls().size())
                    .as("%s : %d api calls with %d elements, budget %d (its count with %d): it asks for them one at"
                                    + " a time; fix it, or note it n+1%n%s",
                            row.label(), atLarge.calls().size(), FakeApi.LARGE, atSmall.calls().size(),
                            FakeApi.SMALL, paths(atLarge))
                    .isEqualTo(atSmall.calls().size());
        }
        softly.assertAll();
    }

    private static String paths(Measure measure) {
        return String.join("\n", measure.calls());
    }

    /** One warm-up call on a fresh session, then {@value #CALLS}: the last one's calls and answer, the median time. */
    private Measure measure(Row row) throws Exception {
        String cookie = login(row.caller());
        call(row, cookie);
        long[] millis = new long[CALLS];
        Measure last = null;
        for (int i = 0; i < CALLS; i++) {
            last = call(row, cookie);
            millis[i] = last.millis();
        }
        Arrays.sort(millis);
        Measure measured = Objects.requireNonNull(last);
        return new Measure(measured.status(), measured.calls(), millis[CALLS / 2], measured.body());
    }

    /** {@code row}'s page once, its caches emptied first (see the class comment). */
    private Measure call(Row row, String cookie) throws Exception {
        expireCaches();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + row.uri()))
                .header("Cookie", cookie)
                .GET()
                .build();
        FAKE.mark();
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Measure(response.statusCode(), FAKE.pageCalls(), millis, response.body());
    }

    private void expireCaches() throws InterruptedException {
        channelIds.forget(FakeApi.OWNER_LOGIN);
        for (UUID member : List.of(FakeApi.OWNER, FakeApi.VISITOR, FakeApi.ADMIN)) {
            debugMembers.evict(member);
        }
        // through Redis, as an admin's change empties it on every instance
        metaTagsCleared = new CountDownLatch(1);
        metaTags.clear();
        assertThat(metaTagsCleared.await(5, TimeUnit.SECONDS)).as("the meta tags were emptied").isTrue();
    }

    /** A new session in Redis, logged in as {@code caller}: the cookie that names it. */
    private String login(String caller) {
        String jwt = switch (caller) {
            case "owner" -> FakeApi.jwt(FakeApi.OWNER, List.of("ROLE_USER"));
            case "visitor" -> FakeApi.jwt(FakeApi.VISITOR, List.of("ROLE_USER"));
            case "admin" -> FakeApi.jwt(FakeApi.ADMIN, List.of("ROLE_USER", "ROLE_ADMIN"));
            default -> throw new IllegalArgumentException(caller);
        };
        String id = save(sessions, jwt);
        // Spring Session's default cookie serializer writes the id in Base64
        return "JSESSIONID=" + Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private static <S extends Session> String save(SessionRepository<S> repository, String jwt) {
        S session = repository.createSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        repository.save(session);
        return session.getId();
    }

    /** One page's status, catapult-api calls, time and body. */
    private record Measure(int status, List<String> calls, long millis, String body) {}

    /** One line of {@value #BUDGETS}; {@code apiCalls} is null while the budget is not written yet. */
    record Row(int line, String method, String path, String caller, Integer apiCalls, String notes) {

        boolean fixed() {
            return notes.startsWith(FIXED);
        }

        boolean nPlusOne() {
            return N_PLUS_ONE.equals(notes);
        }

        String uri() {
            return path.replace("{id}", FakeApi.EXPERIMENT.toString());
        }

        /** As the failure messages name a row: {@code GET /channel/… [owner]}. */
        String label() {
            return method + " " + path + " [" + caller + "]";
        }

        @Override
        public String toString() {
            return label();
        }

        static List<Row> parse(String resource) throws IOException {
            InputStream in = WebPageBudgetTest.class.getClassLoader().getResourceAsStream(resource);
            Objects.requireNonNull(in, resource);
            List<Row> rows = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                boolean header = true;
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    List<String> cells = List.of(line.split(",", -1));
                    if (header) {
                        assertThat(cells).as("%s header", resource).isEqualTo(HEADER);
                        header = false;
                        continue;
                    }
                    assertThat(cells).as("%s line %d", resource, number).hasSize(HEADER.size());
                    assertThat(cells.get(0)).as("%s line %d, method", resource, number).isEqualTo("GET");
                    assertThat(cells.get(2)).as("%s line %d, caller", resource, number).isIn(CALLERS);
                    String notes = cells.get(4);
                    assertThat(notes.isEmpty() || N_PLUS_ONE.equals(notes)
                                    || (notes.startsWith(FIXED) && !notes.substring(FIXED.length()).isBlank()))
                            .as("%s line %d, notes: empty, n+1 or fixed:<why>, not %s", resource, number, notes)
                            .isTrue();
                    String calls = cells.get(3);
                    rows.add(new Row(number, cells.get(0), cells.get(1), cells.get(2),
                            calls.isEmpty() ? null : Integer.valueOf(calls), notes));
                }
            }
            return rows;
        }
    }
}
