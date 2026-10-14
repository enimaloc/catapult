package fr.enimaloc.catapult.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.IntSupplier;

/**
 * What members do on catapult-web, for Prometheus:
 * <ul>
 *     <li>{@code catapult.web.page.views}: a page shown, by {@code page}, {@code via} (a full load or
 *     the SPA's fragment), the visitor's {@code role} and the {@code outcome};</li>
 *     <li>{@code catapult.web.actions}: a dashboard action (a setting saved, a binding changed...),
 *     timed, by {@code area}, {@code action}, {@code role} and {@code outcome};</li>
 *     <li>{@code catapult.web.logins}: a login completed or refused;</li>
 *     <li>{@code catapult.web.sse.streams}: the live update streams open, by {@code stream}, and
 *     {@code catapult.web.sse.refused} / {@code catapult.web.sse.evicted}: those turned away (logged
 *     out, not their channel) or closed for a newer one of the same session.</li>
 * </ul>
 * A role is {@code anonymous}, {@code member} or {@code admin}, or on a channel its {@code owner}
 * or a {@code moderator} (see InteractionMetricsInterceptor).
 */
@Component
@RequiredArgsConstructor
public class InteractionMetrics {
    private final MeterRegistry registry;

    public void pageView(String page, String via, String role, int status) {
        Counter.builder("catapult.web.page.views")
                .description("Pages shown to visitors")
                .tags("page", page, "via", via, "role", role, "outcome", outcome(status))
                .register(registry)
                .increment();
    }

    public void action(String area, String action, String role, int status, Duration duration) {
        Timer.builder("catapult.web.actions")
                .description("Dashboard actions taken by members")
                .tags("area", area, "action", action, "role", role, "outcome", outcome(status))
                .register(registry)
                .record(duration);
    }

    public void login(boolean succeeded) {
        Counter.builder("catapult.web.logins")
                .description("Logins completed (success) or refused by catapult-api (failure)")
                .tag("outcome", succeeded ? "success" : "failure")
                .register(registry)
                .increment();
    }

    /** Tracks how many {@code stream} streams are open, as {@code open} tells. */
    public void streams(String stream, IntSupplier open) {
        Gauge.builder("catapult.web.sse.streams", open::getAsInt)
                .description("Live update streams open")
                .tag("stream", stream)
                .register(registry);
    }

    /** A {@code stream} stream turned away: {@code unauthenticated} or {@code forbidden}. */
    public void streamRefused(String stream, String reason) {
        Counter.builder("catapult.web.sse.refused")
                .description("Live update streams refused")
                .tags("stream", stream, "reason", reason)
                .register(registry)
                .increment();
    }

    /** A {@code stream} stream closed because its session opened one too many. */
    public void streamEvicted(String stream) {
        Counter.builder("catapult.web.sse.evicted")
                .description("Live update streams closed for a newer one of the same session")
                .tag("stream", stream)
                .register(registry)
                .increment();
    }

    /** {@code success} below 400, then {@code client_error} and {@code server_error}. */
    static String outcome(int status) {
        if (status >= 500) return "server_error";
        if (status >= 400) return "client_error";
        return "success";
    }
}
