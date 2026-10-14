package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.controller.ErrorPageController;
import fr.enimaloc.catapult.controller.IndexController;
import fr.enimaloc.catapult.service.ApiService;
import io.sentry.BaggageHeader;
import io.sentry.Sentry;
import io.sentry.SentryTraceHeader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The browser's Sentry setup, for the full pages (index.html loads the SDK and monitoring.js only
 * with it): the same DSN, environment and release as the server's, and the request's trace, which
 * the page's own then continues, and the member's account id (as SentryUserInterceptor names them).
 * None without a DSN.
 */
@ControllerAdvice(assignableTypes = {IndexController.class, ErrorPageController.class})
public class SentryBrowser {
    /**
     * The SDK's scripts index.html loads, in order, each with its SRI hash: change both together.
     * They are Sentry's CDN files for that version, served from here under names blockers don't
     * know, as they drop browser.sentry-cdn.com: bundle.tracing.replay.min.js (whose hash is the one
     * Sentry publishes), then httpclient.min.js, the integration reporting failed requests.
     */
    static final Map<String, String> SDK = sdk(
            "/js/vendor/reporting-11.7.0.min.js",
            "sha384-WY7ZjnbIXCRToFqtQq549yOktNYUeKgF2oxr7kmBBEuy8L1kJFW94lu++diDUDPS",
            "/js/vendor/reporting-httpclient-11.7.0.min.js",
            "sha384-P7AqJ2m2VFTVDY0Nigx27KSQ17gNO7NFU5inGTTlkNrWs5qcn3X2MI+fLHR0MYYz");

    private static Map<String, String> sdk(String... scriptsAndHashes) {
        Map<String, String> sdk = new LinkedHashMap<>();
        for (int i = 0; i < scriptsAndHashes.length; i += 2) {
            sdk.put(scriptsAndHashes[i], scriptsAndHashes[i + 1]);
        }
        return Collections.unmodifiableMap(sdk);
    }

    /**
     * What monitoring.js initializes Sentry with; {@code trace} and {@code baggage} go in meta tags,
     * {@code sdk}'s scripts and their hashes in the script tags loading them.
     */
    public record Config(String dsn, String environment, String release, double tracesSampleRate,
                         String user, String trace, String baggage, Map<String, String> sdk) {
        /** What monitoring.js reads, as window.Catapult.sentry: Thymeleaf writes a record as {}. */
        public Map<String, Object> script() {
            Map<String, Object> script = new LinkedHashMap<>();
            script.put("dsn", dsn);
            script.put("environment", environment);
            script.put("release", release);
            script.put("tracesSampleRate", tracesSampleRate);
            script.put("user", user);
            return script;
        }
    }

    private final String dsn;
    private final String environment;
    private final Optional<String> release;
    private final double tracesSampleRate;
    private final ObjectProvider<ApiService> apiService;

    public SentryBrowser(@Value("${sentry.dsn:}") String dsn,
                         @Value("${sentry.environment:production}") String environment,
                         @Value("${sentry.traces-sample-rate:1.0}") double tracesSampleRate,
                         Optional<BuildProperties> build, ObjectProvider<ApiService> apiService) {
        this.dsn = dsn;
        this.environment = environment;
        this.tracesSampleRate = tracesSampleRate;
        this.release = SentryConfig.release(build);
        this.apiService = apiService;
    }

    @ModelAttribute("sentry")
    public Config config() {
        if (dsn == null || dsn.isBlank()) {
            return null;
        }
        SentryTraceHeader trace = Sentry.getTraceparent();
        BaggageHeader baggage = Sentry.getBaggage();
        ApiService api = apiService.getIfAvailable();
        UUID account = api == null ? null : api.currentUserId();
        return new Config(dsn, environment, release.orElse(null), tracesSampleRate,
                account == null ? null : account.toString(),
                trace == null ? null : trace.getValue(), baggage == null ? null : baggage.getValue(), SDK);
    }
}
