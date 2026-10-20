package fr.enimaloc.catapult.monitoring;

import io.sentry.CustomSamplingContext;
import io.sentry.SamplingContext;
import io.sentry.SentryOptions;
import io.sentry.TransactionContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SentryConfigTest {

    private final SentryOptions.TracesSamplerCallback sampler = new SentryConfig().tracesSampler(0.25);

    /** The sampling of a request to {@code uri}, its trace started by the browser as {@code parentSampled}. */
    private Double sample(String uri, Boolean parentSampled) {
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
