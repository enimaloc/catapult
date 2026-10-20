package fr.enimaloc.catapult.monitoring;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What catapult-web tells Sentry beyond its properties: the release it runs and which requests to
 * trace (the member a report comes from is {@link SentryUserInterceptor}'s).
 */
@Configuration
public class SentryConfig {
    /**
     * Requests not worth a trace: the endless live update streams, health probes, static assets and
     * the browser's own reports.
     */
    private static final Pattern UNTRACED = Pattern.compile(
            "^/(events/|me/twitchat/events|widget/|up$|actuator/|css/|js/|_static/|assets/|favicon|monitoring/tunnel).*");

    /** {@code catapult-web@<commit>}, the commit catapult-web was built from; the browser reports under it too. */
    public static Optional<String> release(Optional<BuildProperties> build) {
        return build.map(properties -> properties.get("git.commit"))
                .filter(commit -> !commit.isBlank() && !commit.equals("unknown"))
                .map(commit -> "catapult-web@" + commit);
    }

    @Bean
    Sentry.OptionsConfiguration<SentryOptions> sentryRelease(Optional<BuildProperties> build) {
        return options -> release(build).ifPresent(options::setRelease);
    }

    /**
     * A trace the browser started (it sends sentry-trace with its fetches) goes on as it decided;
     * other requests are traced at {@code sentry.traces-sample-rate}, but for the {@link #UNTRACED}.
     */
    @Bean
    SentryOptions.TracesSamplerCallback tracesSampler(@Value("${sentry.traces-sample-rate:1.0}") double rate) {
        return context -> {
            if (context.getCustomSamplingContext() != null
                    && context.getCustomSamplingContext().get("request") instanceof HttpServletRequest request
                    && UNTRACED.matcher(request.getRequestURI()).matches()) {
                return 0.0;
            }
            Boolean parentSampled = context.getTransactionContext().getParentSampled();
            return parentSampled != null ? (parentSampled ? 1.0 : 0.0) : rate;
        };
    }
}
