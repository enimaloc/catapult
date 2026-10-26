package fr.enimaloc.catapult.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamPlaytestRedirectResolver;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.JwtService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.minecraft.MsaAuthClient;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.xbox.XboxService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * How many SQL statements, and how long, each row of {@value #BUDGETS} takes, on the whole application (real
 * security chains, real database) set up as the access matrix is. Each row is called once to warm up, then
 * {@value #CALLS} times: the SQL count is the last call's, the time the median of the {@value #CALLS}. It is done
 * with {@value BudgetFixtures#SMALL} elements in every collection the routes show, then again with
 * {@value BudgetFixtures#LARGE} (see {@link BudgetFixtures}).
 *
 * <p>A row fails when its route answers anything but a 2xx or a redirect, takes more SQL statements than its
 * {@code sql} budget (the count measured when the row was written: lower one that drops), takes more than
 * {@value #MAX_MILLIS} ms, or takes more statements at {@value BudgetFixtures#LARGE} elements than at
 * {@value BudgetFixtures#SMALL}: it then reads its collection one element at a time. A row whose {@code notes} is
 * {@code n+1} is such a route, known and left as it is: only its count at {@value BudgetFixtures#LARGE} is held to
 * its budget. A row whose {@code notes} is {@code fixed:<reason>} (no comma) answers the same at both sizes, for that
 * reason: it shows none of the collections.
 *
 * <p>Callers ({@code caller}): {@code owner} (the channel), {@code visitor} (moderates the owner's channel),
 * {@code admin} ({@code ROLE_ADMIN}), with a real JWT each, {@code ptoken} (the owner's personal API token) and
 * {@code anon} (no header), all from a remote address. {@code vars} is as in {@code access-matrix.csv}; a variable
 * it leaves out is {@code {userId}} the owner, {@code {uuid}} the owner's widget token, {@code {bindingId}} the
 * owner's first game binding, {@code {id}} the first budget experiment.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.config.additional-location=file:./src/main/resources/")
@TestPropertySource(
        locations = "classpath:full-context.properties",
        properties = {
            "xbox.enabled=true",
            "xbox.client-id=full-context-test",
            "xbox.client-secret=full-context-test",
            "minecraft.enabled=true",
            "minecraft.msa-client-id=full-context-test",
            "dtdd.enabled=true",
            // the counting proxy stands for the pool, which Spring Cloud must not rebind either
            "spring.cloud.refresh.never-refreshable=com.zaxxer.hikari.HikariDataSource,"
                    + "net.ttddyy.dsproxy.support.ProxyDataSource"
        })
@Import({FullContextContainers.class, SqlCounter.Config.class, BudgetFixtures.class})
@AutoConfigureMockMvc
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteBudgetTest {

    static final String BUDGETS = "route-budgets.csv";
    static final int CALLS = 5;
    static final long MAX_MILLIS = 500;
    private static final String REMOTE = "203.0.113.7";
    private static final String DEAD = "00000000-0000-0000-0000-00000000dead";
    /** The Microsoft Store product the {@code /api/user/xbox/{productId}} row asks for. */
    private static final String XBOX_PRODUCT_ID = "9NBLGGH4R315";
    private static final List<String> CALLERS = List.of("owner", "visitor", "admin", "ptoken", "anon");
    private static final List<String> HEADER = List.of("method", "pattern", "vars", "caller", "sql", "notes");
    private static final String N_PLUS_ONE = "n+1";
    private static final String FIXED = "fixed:";
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    @MockitoBean
    TwitchService twitchService;

    @MockitoBean
    SteamStoreService steamStoreService;

    @MockitoBean
    XboxService xboxService;

    @MockitoBean
    MsaAuthClient msaAuthClient;

    @MockitoBean
    SteamApiClient steamApiClient;

    @MockitoBean
    SteamPlaytestRedirectResolver steamPlaytestRedirectResolver;

    @MockitoBean
    IgdbService igdbService;

    @MockitoBean
    XboxStoreService xboxStoreService;

    @Autowired
    MockMvc mvc;

    @Autowired
    BudgetFixtures fixtures;

    @Autowired
    JwtService jwtService;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    private BudgetFixtures.Accounts data;
    private final Map<String, String> authorization = new HashMap<>();
    private final Map<Row, Measure> small = new HashMap<>();
    private final Map<Row, Measure> large = new HashMap<>();

    /**
     * Measures every row at both sizes up front, so that each row's test only reads its measures. Twitch says the
     * owner moderates every budget channel (those without an account yet are just not found) and the visitor the
     * owner's.
     */
    @BeforeAll
    void measure() throws Exception {
        data = fixtures.create();
        when(twitchService.getModeratedChannelIds(any())).thenReturn(List.of());
        when(twitchService.getModeratedChannelIds(argThat(a -> a != null && data.owner().getId().equals(a.getId()))))
                .thenReturn(data.channelTwitchIds());
        when(twitchService.getModeratedChannelIds(argThat(a -> a != null && data.visitor().getId().equals(a.getId()))))
                .thenReturn(List.of(data.owner().getTwitchId()));
        stubLookups();
        authorization.put("owner", bearer(data.owner(), List.of("ROLE_USER")));
        authorization.put("visitor", bearer(data.visitor(), List.of("ROLE_USER")));
        authorization.put("admin", bearer(data.admin(), List.of("ROLE_USER", "ROLE_ADMIN")));
        authorization.put("ptoken", "Bearer " + data.ptoken());
        List<Row> rows = Row.parse(BUDGETS);
        for (Row row : rows) {
            small.put(row, measure(row));
        }
        fixtures.grow(data);
        for (Row row : rows) {
            large.put(row, measure(row));
        }
        for (Row row : rows) {
            System.out.printf("route budget %s: HTTP %d, SQL %d at %d, %d at %d; %d ms, %d ms%n", row,
                    large.get(row).status(), small.get(row).sql(), BudgetFixtures.SMALL, large.get(row).sql(),
                    BudgetFixtures.LARGE, small.get(row).millis(), large.get(row).millis());
        }
    }

    /** IGDB knows the owner's game, the Steam store and the Microsoft Store have a page for it. */
    private void stubLookups() throws JacksonException {
        when(igdbService.findBySteamAppId(BudgetFixtures.STEAM_APP_ID))
                .thenReturn(Optional.of(new IgdbService.IgdbGame(BudgetFixtures.IGDB_ID, "Budget game 0")));
        SteamStoreService.SteamStorePage page = JsonMapper.builder().build().readValue(
                "{\"type\":\"game\",\"name\":\"Budget game 0\",\"required_age\":0,\"is_free\":false,"
                        + "\"steam_appid\":" + BudgetFixtures.STEAM_APP_ID + "}",
                SteamStoreService.SteamStorePage.class);
        when(steamStoreService.fetchData(eq(BudgetFixtures.STEAM_APP_ID), any(), anyBoolean()))
                .thenReturn(Optional.of(page));
        when(xboxStoreService.fetchProduct(eq(XBOX_PRODUCT_ID), any())).thenReturn(Optional.of(
                new XboxStoreService.XboxProduct("Budget game 0", "Budget description", "Budget publisher",
                        "Budget developer", null, null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                        null)));
    }

    private String bearer(UserAccount account, List<String> roles) {
        return "Bearer " + jwtService.generateForUser(account, roles);
    }

    static List<Row> rows() throws IOException {
        return Row.parse(BUDGETS);
    }

    @Test
    void sqlCounterCountsEveryStatement() {
        SqlCounter.reset();
        accounts.count();
        accounts.count();
        assertThat(SqlCounter.count()).isEqualTo(2);
    }

    /**
     * A row whose answer does not move with the fixture does not prove its count holds whatever the size; a row noted
     * {@code fixed} must indeed answer the same at both sizes, so that the note cannot hide one that should move.
     */
    @Test
    void grownFixtureChangesTheResponseBody() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        for (Row row : rows()) {
            if (row.fixed()) {
                softly.assertThat(large.get(row).body())
                        .as("%s is noted %s but answers differently with %d elements: drop the note", row,
                                row.notes(), BudgetFixtures.LARGE)
                        .isEqualTo(small.get(row).body());
                continue;
            }
            softly.assertThat(large.get(row).body())
                    .as("%s answers the same with %d elements as with %d: grow what it shows, or note it fixed:<why>",
                            row, BudgetFixtures.LARGE, BudgetFixtures.SMALL)
                    .isNotEqualTo(small.get(row).body());
        }
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void routeStaysWithinBudget(Row row) {
        Measure atSmall = small.get(row);
        Measure atLarge = large.get(row);
        SoftAssertions softly = new SoftAssertions();
        for (Measure measure : List.of(atSmall, atLarge)) {
            // a redirect is the answer of a route that redirects (the Steam OpenID callback)
            softly.assertThat(measure.status())
                    .as("%s answered %d: %s", row.label(), measure.status(), measure.body())
                    .isBetween(200, 399);
            softly.assertThat(measure.millis())
                    .as("%s : %d ms, budget %d", row.label(), measure.millis(), MAX_MILLIS)
                    .isLessThanOrEqualTo(MAX_MILLIS);
        }
        String budget = row.sql() == null ? "none" : String.valueOf(row.sql());
        List<Measure> held = row.nPlusOne() ? List.of(atLarge) : List.of(atSmall, atLarge);
        for (Measure measure : held) {
            softly.assertThat(row.sql() != null && measure.sql() <= row.sql())
                    .as("%s : %d SQL queries, budget %s", row.label(), measure.sql(), budget)
                    .isTrue();
        }
        if (!row.nPlusOne()) {
            softly.assertThat(atLarge.sql())
                    .as("%s : %d SQL queries with %d elements, budget %d (its count with %d): it reads them one at a"
                                    + " time; fix it, or note it n+1",
                            row.label(), atLarge.sql(), BudgetFixtures.LARGE, atSmall.sql(), BudgetFixtures.SMALL)
                    .isEqualTo(atSmall.sql());
        }
        softly.assertAll();
    }

    /** Every row is the method and pattern of a real handler, so a row left behind by a removed route fails. */
    @Test
    void everyBudgetRowHasAHandler() throws IOException {
        Set<String> keys = new HashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> methods = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name)
                    .collect(Collectors.toSet());
            for (String pattern : info.getPatternValues()) {
                methods.forEach(method -> keys.add(method + " " + pattern));
            }
        }
        List<String> stale = rows().stream()
                .filter(r -> !keys.contains(r.key()))
                .map(r -> r.key() + " (line " + r.line() + ")")
                .sorted()
                .toList();
        assertThat(stale).as("rows that match no handler").isEmpty();
    }

    /** One warm-up call, then {@value #CALLS}: the last one's SQL count and answer, the median time. */
    private Measure measure(Row row) throws Exception {
        perform(row);
        long[] millis = new long[CALLS];
        MvcResult result = null;
        int sql = 0;
        for (int i = 0; i < CALLS; i++) {
            SqlCounter.reset();
            long start = System.nanoTime();
            result = perform(row);
            millis[i] = (System.nanoTime() - start) / 1_000_000;
            sql = SqlCounter.count();
        }
        Arrays.sort(millis);
        MvcResult last = Objects.requireNonNull(result);
        return new Measure(last.getResponse().getStatus(), sql, millis[CALLS / 2],
                last.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult perform(Row row) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(row.method()), uri(row));
        builder.with(r -> {
            r.setRemoteAddr(REMOTE);
            return r;
        });
        String auth = authorization.get(row.caller());
        if (auth != null) {
            builder.header(HttpHeaders.AUTHORIZATION, auth);
        }
        return mvc.perform(builder).andReturn();
    }

    /** The row's URI: its {@code vars}, else each variable's default (see the class comment). */
    private String uri(Row row) {
        Map<String, String> given = new LinkedHashMap<>();
        String query = "";
        if (!row.vars().isEmpty()) {
            for (String part : row.vars().split(";")) {
                if (part.startsWith("?")) {
                    query = part;
                } else {
                    given.put(part.substring(0, part.indexOf('=')), part.substring(part.indexOf('=') + 1));
                }
            }
        }
        Matcher matcher = VARIABLE.matcher(row.pattern());
        StringBuilder uri = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = given.getOrDefault(name, switch (name) {
                case "userId" -> data.owner().getId().toString();
                case "uuid" -> data.widgetToken().toString();
                case "bindingId" -> data.bindingId().toString();
                case "id" -> data.experimentId().toString();
                default -> DEAD;
            });
            matcher.appendReplacement(uri, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(uri);
        return uri + query;
    }

    /** One row's status, SQL count, median time and body. */
    private record Measure(int status, int sql, long millis, String body) {}

    /** One line of {@value #BUDGETS}; {@code sql} is null while the budget is not written yet. */
    record Row(int line, String method, String pattern, String vars, String caller, Integer sql, String notes) {

        boolean fixed() {
            return notes.startsWith(FIXED);
        }

        boolean nPlusOne() {
            return N_PLUS_ONE.equals(notes);
        }

        String key() {
            return method + " " + pattern;
        }

        /** As the failure messages name a row: {@code GET /api/… [owner]}. */
        String label() {
            return key() + " [" + caller + "]";
        }

        @Override
        public String toString() {
            return label() + (vars.isEmpty() ? "" : " " + vars);
        }

        static List<Row> parse(String resource) throws IOException {
            InputStream in = RouteBudgetTest.class.getClassLoader().getResourceAsStream(resource);
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
                    assertThat(cells.get(3)).as("%s line %d, caller", resource, number).isIn(CALLERS);
                    String notes = cells.get(5);
                    assertThat(notes.isEmpty() || N_PLUS_ONE.equals(notes)
                                    || (notes.startsWith(FIXED) && !notes.substring(FIXED.length()).isBlank()))
                            .as("%s line %d, notes: empty, n+1 or fixed:<why>, not %s", resource, number, notes)
                            .isTrue();
                    String sql = cells.get(4);
                    rows.add(new Row(number, cells.get(0), cells.get(1), cells.get(2), cells.get(3),
                            sql.isEmpty() ? null : Integer.valueOf(sql), notes));
                }
            }
            return rows;
        }
    }
}
