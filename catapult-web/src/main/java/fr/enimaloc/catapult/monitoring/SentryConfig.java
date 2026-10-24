package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.debug.DebugMembers;
import io.sentry.Sentry;
import io.sentry.SentryOptions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.servlet.filter.OrderedFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

    /** The name of the Sentry starter's registration of its tracing filter, which samples each request. */
    static final String TRACING_FILTER = "sentryTracingFilter";
    /**
     * Where that filter runs: after Spring Session's filter (the session lives in Redis, the container's request
     * has none) and Boot's request context filter ({@code OrderedRequestContextFilter}, at
     * {@code REQUEST_WRAPPER_FILTER_MAX_ORDER - 105}), so that the sampler sees the member logged in; still before
     * Spring Security's chain and every other filter of catapult-web.
     */
    static final int TRACING_FILTER_ORDER = OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 104;

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
     * Moves Sentry's tracing filter to {@link #TRACING_FILTER_ORDER}; the starter puts it first of all, before
     * the request has a session, so a member in debug mode could not be told from anyone else when sampling.
     */
    @Bean
    static BeanPostProcessor sentryTracingFilterOrder() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (TRACING_FILTER.equals(beanName) && bean instanceof FilterRegistrationBean<?> registration) {
                    registration.setOrder(TRACING_FILTER_ORDER);
                }
                return bean;
            }
        };
    }

    /**
     * A trace the browser started (it sends sentry-trace with its fetches) goes on as it decided;
     * a member in debug mode is always traced, whatever the browser decided; other requests are traced at {@code sentry.traces-sample-rate}, but for the {@link #UNTRACED}.
     * The degraded profile's {@code catapult.degraded.traces-sample-rate}, when set, caps that rate: it is a
     * key of its own because {@code SENTRY_TRACES_SAMPLE_RATE}, which compose always sets, would win over a
     * profile's {@code sentry.traces-sample-rate}.
     */
    @Bean
    SentryOptions.TracesSamplerCallback tracesSampler(
            @Value("${sentry.traces-sample-rate:1.0}") double rate,
            @Value("${catapult.degraded.traces-sample-rate:-1}") double degradedRate,
            DebugMembers debugMembers) {
        // Negative = the degraded profile is off
        double tracedRate = degradedRate >= 0 ? Math.min(rate, degradedRate) : rate;
        return context -> {
            HttpServletRequest request = context.getCustomSamplingContext() != null
                    && context.getCustomSamplingContext().get("request") instanceof HttpServletRequest r ? r : null;
            if (request != null && UNTRACED.matcher(request.getRequestURI()).matches()) {
                return 0.0;
            }
            // A member in debug mode is traced in full, even when the degraded profile caps the rate. The request
            // is the one sampled: the member is read from its session, bound to the thread or not.
            if (request != null ? debugMembers.isActiveOn(request) : debugMembers.isCurrentActive()) {
                return 1.0;
            }
            Boolean parentSampled = context.getTransactionContext().getParentSampled();
            return parentSampled != null ? (parentSampled ? 1.0 : 0.0) : tracedRate;
        };
    }
}
