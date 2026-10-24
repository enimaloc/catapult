package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.debug.DebugMembers;
import fr.enimaloc.catapult.service.http.ApiClient;
import io.sentry.CustomSamplingContext;
import io.sentry.SamplingContext;
import io.sentry.SentryOptions;
import io.sentry.TransactionContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SentryConfigTest {

    private final SentryOptions.TracesSamplerCallback sampler = new SentryConfig().tracesSampler(0.25, -1, mock(DebugMembers.class));

    /** The sampling of a request to {@code uri}, its trace started by the browser as {@code parentSampled}. */
    private Double sample(String uri, Boolean parentSampled) {
        return sample(sampler, uri, parentSampled);
    }

    private static Double sample(SentryOptions.TracesSamplerCallback sampler, String uri, Boolean parentSampled) {
        TransactionContext transaction = new TransactionContext("GET " + uri, "http.server");
        transaction.setParentSampled(parentSampled);
        CustomSamplingContext custom = new CustomSamplingContext();
        custom.set("request", new MockHttpServletRequest("GET", uri));
        return sampler.sample(new SamplingContext(transaction, custom, 0.5, Map.of()));
    }

    @Test
    void theEndlessStreams_probes_assetsAndReports_areNeverTraced_whateverTheBrowserDecided() {
        assertThat(sample("/events/nav", null)).isZero();
        assertThat(sample("/me/twitchat/events", true)).isZero();
        assertThat(sample("/widget/obs/t/events", null)).isZero();
        assertThat(sample("/actuator/prometheus", null)).isZero();
        assertThat(sample("/js/live.js", true)).isZero();
        assertThat(sample("/monitoring/tunnel", true)).isZero();
    }

    @Test
    void aTraceTheBrowserStarted_goesOnAsItDecided() {
        assertThat(sample("/dashboard", true)).isEqualTo(1.0);
        assertThat(sample("/dashboard", false)).isZero();
    }

    @Test
    void anyOtherRequest_isTracedAtTheConfiguredRate() {
        assertThat(sample("/dashboard", null)).isEqualTo(0.25);

        TransactionContext scheduled = new TransactionContext("heartbeat", "task");
        assertThat(sampler.sample(new SamplingContext(scheduled, null, 0.5, Map.of()))).isEqualTo(0.25);
    }

    @Test
    void theDegradedRate_capsTheConfiguredRate_butNeverRaisesIt() {
        assertThat(sample(new SentryConfig().tracesSampler(1.0, 0.1, mock(DebugMembers.class)), "/dashboard", null)).isEqualTo(0.1);
        assertThat(sample(new SentryConfig().tracesSampler(0.2, 0.5, mock(DebugMembers.class)), "/dashboard", null)).isEqualTo(0.2);
        assertThat(sample(new SentryConfig().tracesSampler(1.0, 0.1, mock(DebugMembers.class)), "/dashboard", true)).isEqualTo(1.0);
        assertThat(sample(new SentryConfig().tracesSampler(1.0, 0.1, mock(DebugMembers.class)), "/events/nav", null)).isZero();
    }

    // ---------------------------------------------------------------- a member in debug mode

    static final UUID DEBUGGED = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    /** A session JWT as catapult-api issues it (only its {@code sub} claim is read here). */
    static String jwt(UUID subject) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    /**
     * catapult-api as {@code RealApiService} reaches it: the member is the bound request's session JWT subject,
     * and only {@link #DEBUGGED} is in debug mode.
     */
    static ApiService api() {
        ApiService api = mock(ApiService.class);
        when(api.currentUserId()).thenAnswer(inv -> ApiClient.currentSubject());
        when(api.myDebugActive()).thenAnswer(inv -> DEBUGGED.equals(ApiClient.currentSubject()));
        return api;
    }

    static DebugMembers debugMembers(ApiService api) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("api", api);
        return new DebugMembers(context.getBeanProvider(ApiService.class));
    }

    private static Double sampleAs(SentryOptions.TracesSamplerCallback sampler, String uri, Boolean parentSampled,
                                   UUID member) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (member != null) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt(member));
            request.setSession(session);
        }
        TransactionContext transaction = new TransactionContext("GET " + uri, "http.server");
        transaction.setParentSampled(parentSampled);
        CustomSamplingContext custom = new CustomSamplingContext();
        custom.set("request", request);
        return sampler.sample(new SamplingContext(transaction, custom, 0.5, Map.of()));
    }

    @Test
    void aMemberInDebugMode_isTracedInFull_fromTheSampledRequestAlone_evenInDegradedMode() {
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
        SentryOptions.TracesSamplerCallback degraded = new SentryConfig().tracesSampler(0.2, 0.1, debugMembers(api()));

        assertThat(sampleAs(degraded, "/dashboard", null, DEBUGGED)).isEqualTo(1.0);
        assertThat(sampleAs(degraded, "/dashboard", false, DEBUGGED)).isEqualTo(1.0);
        assertThat(sampleAs(degraded, "/events/nav", null, DEBUGGED)).isZero();
        assertThat(sampleAs(degraded, "/dashboard", null, OTHER)).isEqualTo(0.1);
        assertThat(sampleAs(degraded, "/dashboard", null, null)).isEqualTo(0.1);
        // the request is bound only for the time of the question
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    void theDebugAnswer_isRemembered_soSamplingDoesNotAskCatapultApiOnEveryRequest() {
        ApiService api = api();
        SentryOptions.TracesSamplerCallback sampler = new SentryConfig().tracesSampler(0.2, -1, debugMembers(api));
        for (int i = 0; i < 5; i++) {
            assertThat(sampleAs(sampler, "/dashboard", null, DEBUGGED)).isEqualTo(1.0);
            assertThat(sampleAs(sampler, "/dashboard", null, OTHER)).isEqualTo(0.2);
        }
        verify(api, times(2)).myDebugActive();
        // nobody logged in: nothing to ask
        sampleAs(sampler, "/dashboard", null, null);
        verify(api, times(2)).myDebugActive();
    }

    @Test
    void aFailingDebugCheck_neverBreaksTheSampling() {
        ApiService api = mock(ApiService.class);
        when(api.currentUserId()).thenThrow(new IllegalStateException("down"));
        SentryOptions.TracesSamplerCallback sampler = new SentryConfig().tracesSampler(0.2, -1, debugMembers(api));
        assertThat(sampleAs(sampler, "/dashboard", null, DEBUGGED)).isEqualTo(0.2);
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    void theRelease_isTheCommitCatapultWebWasBuiltFrom_whenItIsKnown() {
        assertThat(SentryConfig.release(build("abc123"))).contains("catapult-web@abc123");
        assertThat(SentryConfig.release(build("unknown"))).isEmpty();
        assertThat(SentryConfig.release(build(" "))).isEmpty();
        assertThat(SentryConfig.release(Optional.empty())).isEmpty();

        SentryOptions options = new SentryOptions();
        new SentryConfig().sentryRelease(build("abc123")).configure(options);
        assertThat(options.getRelease()).isEqualTo("catapult-web@abc123");
    }

    private static Optional<BuildProperties> build(String commit) {
        Properties properties = new Properties();
        properties.setProperty("git.commit", commit);
        return Optional.of(new BuildProperties(properties));
    }
}
