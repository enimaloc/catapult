package fr.enimaloc.catapult.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamPlaytestRedirectResolver;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.security.JwtService;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Who reaches every catapult-api route today: each row of {@link #matrixResource()} is one handler method (plus a
 * few springdoc, {@code SecurityConfig} and {@code unmapped} rows), run for seven callers on the whole application
 * (real security chains, real database), and each caller's outcome is pinned.
 *
 * <p>Callers: {@code anon} (remote {@value #REMOTE}, no header), {@code loop} (remote {@value #LOOPBACK}, no
 * header), {@code member}, {@code mod} (moderates the channel), {@code owner} (the channel, {@value #OWNER}),
 * {@code admin} ({@code ROLE_ADMIN}) with a real JWT, and {@code ptoken} (the owner's personal API token), all
 * remote. Outcomes: {@code 401}/{@code 403} refused, {@code GATED} a feature gate's 403 (reached the handler),
 * {@code ERROR} the request threw (a 500 on a real server), {@code PASS} anything else.
 *
 * <p>Every outbound client whose host is hard-coded is a mock, so no row calls a third party: {@link TwitchService}
 * (which also says who moderates the channel), {@link SteamStoreService}, {@link XboxService},
 * {@link MsaAuthClient}, {@link SteamApiClient} and {@link SteamPlaytestRedirectResolver}, and the two a run was
 * seen to call: {@link IgdbService} (its app token, from {@code id.twitch.tv}) and {@link XboxStoreService} (the
 * Microsoft display catalog). The configurable ones point at a closed local port ({@code full-context.properties}).
 *
 * <p>Each caller's request runs in its own transaction, rolled back after it, so no caller sees what another did
 * (a slug one created, a statement that failed and aborted the transaction); the accounts are committed once, before
 * the rows.
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
            "dtdd.enabled=true"
        })
@Import(FullContextContainers.class)
@AutoConfigureMockMvc
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AccessMatrixSupport {

    static final String OWNER = "matrix-owner";
    static final String REMOTE = "203.0.113.7";
    static final String LOOPBACK = "127.0.0.1";
    static final String DEAD = "00000000-0000-0000-0000-00000000dead";
    static final List<String> CALLERS = List.of("anon", "loop", "member", "mod", "owner", "admin", "ptoken");
    /**
     * The order the callers run in, within a row: the owner last, so what the owner does to the channel outside the
     * rolled-back transaction (caches, events) cannot change what the others get.
     */
    private static final List<String> RUN_ORDER = List.of("anon", "loop", "member", "mod", "admin", "ptoken", "owner");
    private static final Set<String> GATE_REASONS = Set.of(
            "Feature not enabled", "Feature Minecraft indisponible", "Raid settings not available for this channel");
    /**
     * The request never reached its handler: the row pins nothing. Bean validation of the arguments
     * ({@code @Valid}, {@code @Validated}) is in the list too, for the same reason: it answers 400 before the handler.
     */
    private static final List<Class<? extends Exception>> UNREACHED = List.of(
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            NoResourceFoundException.class);
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
    TransactionOperations transactions;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    JwtService jwtService;

    @Autowired
    UserApiTokenService userApiTokenService;

    @Autowired
    WidgetTokenService widgetTokenService;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    ExperimentRepository experiments;

    @Autowired
    ExperimentAssignmentRepository assignments;

    UserAccount owner;
    UserAccount mod;
    UserAccount member;
    UserAccount admin;
    /** Caller name to {@code Authorization} header value; anon and loop have none. */
    final Map<String, String> authorization = new LinkedHashMap<>();
    String ptoken;
    UUID widgetToken;

    protected abstract String matrixResource();

    @BeforeAll
    void fixtures() {
        transactions.executeWithoutResult(status -> {
            owner = account(OWNER, "matrix-owner-twitch");
            mod = account("matrix-mod", "matrix-mod-twitch");
            member = account("matrix-member", "matrix-member-twitch");
            admin = account("matrix-admin", "matrix-admin-twitch");
            ptoken = userApiTokenService.regenerate(owner).token();
            widgetToken = widgetTokenService.getOrCreate(owner);
            openCustomBindingsTo(owner);
        });
        authorization.put("member", "Bearer " + jwtService.generateForUser(member, List.of("ROLE_USER")));
        authorization.put("mod", "Bearer " + jwtService.generateForUser(mod, List.of("ROLE_USER")));
        authorization.put("owner", "Bearer " + jwtService.generateForUser(owner, List.of("ROLE_USER")));
        authorization.put(
                "admin", "Bearer " + jwtService.generateForUser(admin, List.of("ROLE_USER", "ROLE_ADMIN")));
        authorization.put("ptoken", "Bearer " + ptoken);
    }

    /**
     * The matrix pins access, not the custom bindings feature gate (its own tests pin that): the owner is let into the
     * {@value CustomBindingService#EXPERIMENT_KEY} experiment as the chat-commands integration test does, by making it
     * ACTIVE and assigning them its {@code enabled} variant. Everyone else stays out of it.
     */
    private void openCustomBindingsTo(UserAccount account) {
        Experiment experiment = experiments.findByKey(CustomBindingService.EXPERIMENT_KEY)
                .orElseThrow(() -> new IllegalStateException("custom bindings experiment not seeded"));
        experiment.setStatus(Experiment.Status.ACTIVE);
        experiments.save(experiment);
        ExperimentVariant enabled = experiment.getVariants().stream()
                .filter(v -> "enabled".equals(v.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("custom bindings experiment has no enabled variant"));
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(enabled);
        assignment.setUser(account);
        assignments.save(assignment);
    }

    /** Mocks are reset after each test, so the moderator is told again before each. */
    @BeforeEach
    void moderator() {
        when(twitchService.getModeratedChannelIds(any())).thenReturn(List.of());
        when(twitchService.getModeratedChannelIds(argThat(a -> a != null && mod.getId().equals(a.getId()))))
                .thenReturn(List.of(owner.getTwitchId()));
    }

    private UserAccount account(String username, String twitchId) {
        UserAccount account = new UserAccount();
        account.setTwitchUsername(username);
        account.setTwitchId(twitchId);
        account.setStatus(UserAccount.Status.ACTIVE);
        return accounts.save(account);
    }

    protected Stream<Row> rows() throws IOException {
        return Row.parse(matrixResource()).stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void accessIsAsPinned(Row row) {
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (String caller : RUN_ORDER) {
            outcomes.put(caller, run(row, caller));
        }
        SoftAssertions softly = new SoftAssertions();
        for (String caller : CALLERS) {
            Outcome outcome = outcomes.get(caller);
            softly.assertThat(outcome.unreached())
                    .as("%s as %s never reached its handler (%s): fix vars/body", row, caller, outcome.detail())
                    .isFalse();
            softly.assertThat(outcome.token())
                    .as("%s as %s (%s)", row, caller, outcome.detail())
                    .isEqualTo(row.expected().get(caller));
        }
        softly.assertAll();
    }

    private Outcome run(Row row, String caller) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(row.method()), row.uri(this));
        String remote = "loop".equals(caller) ? LOOPBACK : REMOTE;
        builder.with(r -> {
            r.setRemoteAddr(remote);
            return r;
        });
        String auth = authorization.get(caller);
        if (auth != null) {
            builder.header(HttpHeaders.AUTHORIZATION, auth);
        }
        row.headers().forEach(builder::header);
        if (Set.of("POST", "PUT", "PATCH").contains(row.method())) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(row.body().isEmpty() ? "{}" : row.body());
        }
        MvcResult result;
        try {
            result = rolledBack(() -> mvc.perform(builder).andReturn());
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return new Outcome("ERROR", false, "threw " + cause);
        }
        Exception resolved = result.getResolvedException();
        int status = result.getResponse().getStatus();
        boolean unreached =
                resolved != null && !row.unmapped() && UNREACHED.stream().anyMatch(t -> t.isInstance(resolved));
        String detail = status + (resolved == null ? "" : " " + resolved);
        if (status == 403
                && resolved instanceof ResponseStatusException rse
                && rse.getReason() != null
                && GATE_REASONS.contains(rse.getReason())) {
            return new Outcome("GATED", unreached, detail);
        }
        if (status == 401 || status == 403) {
            return new Outcome(String.valueOf(status), unreached, detail);
        }
        return new Outcome("PASS", unreached, detail);
    }

    /** {@code work}'s result, in a transaction that is rolled back whatever happens; its failure is rethrown. */
    private <T> T rolledBack(Callable<T> work) throws Exception {
        List<Exception> failure = new ArrayList<>(1);
        T value = transactions.execute(status -> {
            status.setRollbackOnly();
            try {
                return work.call();
            } catch (Exception e) {
                failure.add(e);
                return null;
            }
        });
        if (!failure.isEmpty()) {
            throw failure.get(0);
        }
        return value;
    }

    /** One caller's {@code token}; {@code detail} (status, resolved exception) only explains a failure. */
    private record Outcome(String token, boolean unreached, String detail) {}

    @Test
    void everyHandlerHasARow() throws IOException {
        Map<String, Long> rowsByKey = Row.parse(matrixResource()).stream()
                .filter(r -> !r.unmapped())
                .collect(Collectors.groupingBy(Row::key, TreeMap::new, Collectors.counting()));
        List<String> missing = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getName().startsWith("fr.enimaloc.catapult.")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<String> methods = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name)
                    .collect(Collectors.toSet());
            assertThat(methods).as("%s maps no HTTP method", entry.getValue()).isNotEmpty();
            for (String pattern : info.getPatternValues()) {
                for (String method : methods) {
                    String key = method + " " + pattern;
                    long count = rowsByKey.getOrDefault(key, 0L);
                    if (count == 0) {
                        missing.add(key + "  (" + entry.getValue().getShortLogMessage() + ")");
                    } else if (count > 1) {
                        duplicated.add(key);
                    }
                }
            }
        }
        missing.sort(null);
        assertThat(missing).as("handlers without a row in %s", matrixResource()).isEmpty();
        assertThat(duplicated).as("handlers with more than one row in %s", matrixResource()).isEmpty();
    }

    /**
     * One line of the matrix: a request ({@code method}, {@code pattern} with its variables substituted, {@code vars},
     * {@code body}) and the outcome expected for each caller.
     */
    record Row(int line, String method, String pattern, String vars, String body, Map<String, String> expected) {

        boolean unmapped() {
            return "unmapped".equals(vars);
        }

        String key() {
            return method + " " + pattern;
        }

        private Map<String, String> variables() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String part : parts()) {
                if (!part.startsWith("?") && !part.startsWith("header:") && part.contains("=")) {
                    values.put(part.substring(0, part.indexOf('=')), part.substring(part.indexOf('=') + 1));
                }
            }
            return values;
        }

        Map<String, String> headers() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String part : parts()) {
                if (part.startsWith("header:")) {
                    String header = part.substring("header:".length());
                    values.put(header.substring(0, header.indexOf('=')), header.substring(header.indexOf('=') + 1));
                }
            }
            return values;
        }

        private String query() {
            return parts().stream().filter(p -> p.startsWith("?")).findFirst().orElse("");
        }

        private List<String> parts() {
            if (vars.isEmpty() || unmapped()) {
                return List.of();
            }
            return List.of(vars.split(";"));
        }

        String uri(AccessMatrixSupport matrix) {
            Map<String, String> given = variables();
            Matcher matcher = VARIABLE.matcher(pattern);
            StringBuilder uri = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = given.get(name);
                if (value == null) {
                    value = switch (name) {
                        case "username" -> OWNER;
                        case "ownerId" -> matrix.owner.getId().toString();
                        case "uuid" -> matrix.widgetToken.toString();
                        default -> DEAD;
                    };
                }
                matcher.appendReplacement(uri, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(uri);
            return uri + query();
        }

        @Override
        public String toString() {
            return key() + (vars.isEmpty() ? "" : " [" + vars + "]");
        }

        static List<Row> parse(String resource) throws IOException {
            InputStream in = AccessMatrixSupport.class.getClassLoader().getResourceAsStream(resource);
            Objects.requireNonNull(in, resource);
            List<Row> rows = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> header = null;
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    List<String> cells = cells(line);
                    if (header == null) {
                        header = cells;
                        assertThat(header).as("%s header", resource).containsExactly(Stream.concat(
                                        Stream.of("method", "pattern", "vars", "body"), CALLERS.stream())
                                .toArray(String[]::new));
                        continue;
                    }
                    assertThat(cells).as("%s line %d", resource, number).hasSize(header.size());
                    Map<String, String> expected = new LinkedHashMap<>();
                    for (int i = 0; i < CALLERS.size(); i++) {
                        String cell = cells.get(4 + i);
                        assertThat(cell).as("%s line %d, %s", resource, number, CALLERS.get(i))
                                .isIn("401", "403", "GATED", "ERROR", "PASS");
                        expected.put(CALLERS.get(i), cell);
                    }
                    rows.add(new Row(number, cells.get(0), cells.get(1), cells.get(2), cells.get(3), expected));
                }
            }
            return rows;
        }

        /** Splits one CSV line; a cell in double quotes may hold commas, and {@code ""} is a quote. */
        private static List<String> cells(String line) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    cells.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            cells.add(cell.toString());
            return cells;
        }
    }
}
