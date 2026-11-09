package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange;
import io.sentry.Breadcrumb;
import io.sentry.IScopes;
import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.SentryAttribute;
import io.sentry.SentryAttributes;
import io.sentry.SentryLevel;
import io.sentry.SentryLogLevel;
import io.sentry.SpanId;
import io.sentry.logger.SentryLogParameters;
import io.sentry.protocol.User;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Sends each exchange recorded for a member in debug mode to Sentry, as stored (secrets and other members already
 * masked unless revealed): a log {@code debug exchange <direction> <service> <method> <url>} carrying the details,
 * on the trace the exchange was made in (the one catapult-web's replay of the member's navigation belongs to), and
 * an {@code http} breadcrumb. Runs on the recorder's thread as the member, on scopes of its own; it makes no HTTP
 * call of its own (that would be recorded in turn), only Sentry's.
 * <p>
 * The log is what reaches Sentry: the breadcrumb lives on these scopes, dropped once the exchange is sent, so it
 * only goes out with an error captured while sending it (Sentry sends breadcrumbs with events only). The member's
 * own errors carry the HTTP client's breadcrumbs of the same calls, made on the member's thread.
 */
@Component
public class DebugSentryBridge implements DebugExchangeListener {

    @Override
    public void onRecorded(UUID userId, DebugExchange exchange) {
        if (!Sentry.isEnabled() || userId == null || exchange == null) {
            return;
        }
        IScopes scopes = Sentry.forkedRootScopes("debug.exchange");
        try (ISentryLifecycleToken ignored = scopes.makeCurrent()) {
            if (exchange.getTraceId() != null) {
                scopes.continueTrace(exchange.getTraceId() + "-" + new SpanId(), null);
            }
            User user = new User();
            user.setId(userId.toString());
            scopes.setUser(user);
            scopes.addBreadcrumb(breadcrumb(exchange));
            scopes.logger().log(exchange.isError() ? SentryLogLevel.WARN : SentryLogLevel.INFO,
                    SentryLogParameters.create(attributes(exchange)),
                    "debug exchange %s %s %s %s",
                    exchange.getDirection(), exchange.getService(), exchange.getMethod(), exchange.getUrl());
        }
    }

    private static Breadcrumb breadcrumb(DebugExchange e) {
        Breadcrumb crumb = Breadcrumb.http(e.getUrl(), e.getMethod(), e.getStatus());
        crumb.setLevel(e.isError() ? SentryLevel.ERROR : SentryLevel.INFO);
        crumb.setData("direction", String.valueOf(e.getDirection()));
        crumb.setData("service", e.getService());
        crumb.setData("duration_ms", e.getDurationMs());
        crumb.setData("filtered", e.isFiltered());
        return crumb;
    }

    private static SentryAttributes attributes(DebugExchange e) {
        SentryAttributes attributes = SentryAttributes.of(
                SentryAttribute.stringAttribute("direction", String.valueOf(e.getDirection())),
                SentryAttribute.stringAttribute("service", e.getService()),
                SentryAttribute.stringAttribute("http.request.method", e.getMethod()),
                SentryAttribute.stringAttribute("url.full", e.getUrl()),
                SentryAttribute.named("duration_ms", e.getDurationMs()),
                SentryAttribute.booleanAttribute("filtered", e.isFiltered()));
        if (e.getId() != null) {
            attributes.add(SentryAttribute.stringAttribute("exchange_id", e.getId().toString()));
        }
        if (e.getStatus() != null) {
            attributes.add(SentryAttribute.integerAttribute("http.response.status_code", e.getStatus()));
        }
        addText(attributes, "operation", e.getOperation());
        addText(attributes, "request_headers", e.getRequestHeaders());
        addText(attributes, "request_body", e.getRequestBody());
        addText(attributes, "response_headers", e.getResponseHeaders());
        addText(attributes, "response_body", e.getResponseBody());
        addText(attributes, "error", e.getError());
        return attributes;
    }

    private static void addText(SentryAttributes attributes, String name, String value) {
        if (value != null) {
            attributes.add(SentryAttribute.stringAttribute(name, value));
        }
    }
}
