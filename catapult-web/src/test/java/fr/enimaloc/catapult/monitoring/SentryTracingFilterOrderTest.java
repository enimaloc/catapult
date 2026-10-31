package fr.enimaloc.catapult.monitoring;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.spring7.tracing.SentryTracingFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.filter.OrderedRequestContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sampler, as Sentry's tracing filter calls it in catapult-web's real filter chain: the session lives in Spring
 * Session (Redis in production, a map here), whose filter wraps the request after the servlet container hands it
 * over, so the debugged member is seen only when the tracing filter runs at {@link SentryConfig#TRACING_FILTER_ORDER}.
 */
class SentryTracingFilterOrderTest {

    /** The order the Sentry starter gives its tracing filter (right after its own {@code SentrySpringFilter}). */
    private static final int STARTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;
    private static final UUID OTHER = UUID.randomUUID();

    private final List<Double> sampled = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        Sentry.close();
    }

    @RestController
    static class Page {
        @GetMapping("/dashboard")
        String dashboard() {
            return "ok";
        }
    }

    /** The order catapult-web's context gives the starter's tracing filter registration. */
    private static int configuredTracingOrder() {
        FilterRegistrationBean<SentryTracingFilter> registration = new FilterRegistrationBean<>(new SentryTracingFilter());
        registration.setOrder(STARTER_ORDER);
        SentryConfig.sentryTracingFilterOrder().postProcessBeforeInitialization(registration, SentryConfig.TRACING_FILTER);
        return registration.getOrder();
    }

    private record Ordering(int order, Filter filter) {}

    /** A page request of {@code member}'s session, through the filters sorted as the servlet container would. */
    private Double samplingOf(UUID member, int tracingOrder) throws Exception {
        SentryOptions.TracesSamplerCallback sampler = new SentryConfig().tracesSampler(0.0, -1,
                SentryConfigTest.debugMembers(SentryConfigTest.api()));
        Sentry.init(options -> {
            options.setDsn("https://key@sentry.invalid/1");
            options.setSendClientReports(false);
            options.setTransportFactory((o, request) -> io.sentry.transport.NoOpTransport.getInstance());
            options.setTracesSampler(context -> {
                Double rate = sampler.sample(context);
                sampled.add(rate);
                return rate;
            });
        });

        Map<String, org.springframework.session.Session> sessions = new ConcurrentHashMap<>();
        MapSessionRepository repository = new MapSessionRepository(sessions);
        MapSession session = repository.createSession();
        session.setAttribute(fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY, SentryConfigTest.jwt(member));
        repository.save(session);

        OrderedRequestContextFilter requestContext = new OrderedRequestContextFilter();
        List<Filter> chain = List.of(
                        new Ordering(tracingOrder, new SentryTracingFilter(Sentry.getCurrentScopes())),
                        new Ordering(SessionRepositoryFilter.DEFAULT_ORDER, new SessionRepositoryFilter<>(repository)),
                        new Ordering(requestContext.getOrder(), requestContext))
                .stream().sorted(Comparator.comparingInt(Ordering::order)).map(Ordering::filter).toList();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Page()).addFilters(chain.toArray(Filter[]::new)).build();

        String cookie = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        sampled.clear();
        mvc.perform(get("/dashboard").cookie(new Cookie("SESSION", cookie))).andExpect(status().isOk());
        assertThat(sampled).hasSize(1);
        return sampled.getFirst();
    }

    @Test
    void theTracingFilter_runsAfterTheSessionAndRequestContextFilters_butBeforeSecurity() {
        assertThat(configuredTracingOrder())
                .isGreaterThan(SessionRepositoryFilter.DEFAULT_ORDER)
                .isGreaterThan(new OrderedRequestContextFilter().getOrder())
                .isLessThan(org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    void withTheStarter_itsTracingFilterRegistration_getsTheConfiguredOrder() {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        io.sentry.spring.boot4.SentryAutoConfiguration.class))
                .withUserConfiguration(SentryConfig.class)
                .withBean(fr.enimaloc.catapult.service.debug.DebugMembers.class,
                        () -> SentryConfigTest.debugMembers(SentryConfigTest.api()))
                .withPropertyValues("sentry.dsn=https://key@sentry.invalid/1", "sentry.send-client-reports=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FilterRegistrationBean<?> registration =
                            context.getBean(SentryConfig.TRACING_FILTER, FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(SentryTracingFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(SentryConfig.TRACING_FILTER_ORDER);
                });
    }

    @Test
    void aDebuggedMembersPage_isTracedInFull_inTheRealFilterOrder() throws Exception {
        assertThat(samplingOf(SentryConfigTest.DEBUGGED, configuredTracingOrder())).isEqualTo(1.0);
    }

    @Test
    void anotherMembersPage_staysAtTheConfiguredRate() throws Exception {
        assertThat(samplingOf(OTHER, configuredTracingOrder())).isZero();
    }

    /** What the starter's order did: the sampler ran before the session existed, and missed the member. */
    @Test
    void atTheStartersOrder_theDebuggedMemberWasMissed() throws Exception {
        assertThat(samplingOf(SentryConfigTest.DEBUGGED, STARTER_ORDER)).isZero();
    }
}
