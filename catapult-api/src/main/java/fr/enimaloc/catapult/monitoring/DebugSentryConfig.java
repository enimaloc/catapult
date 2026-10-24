package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.debug.DebugRedactor;
import fr.enimaloc.catapult.service.debug.TraceIdSource;
import io.sentry.CustomSamplingContext;
import io.sentry.IScopes;
import io.sentry.ISentryLifecycleToken;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryAttributeType;
import io.sentry.SentryBaseEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryOptions;
import io.sentry.TransactionContext;
import io.sentry.TransactionOptions;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.User;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * catapult-api reports to Sentry only the work of a member in debug mode: its traces, errors and logs, under that
 * member, and nothing at all for anyone else (with no DSN, Sentry stays off and none of this does anything).
 * <p>
 * Whose work it is comes from {@link DebugContext#subject()} on the thread that captures an error or a log; a
 * transaction, which is sent once that work is over, carries the member in its {@value #MEMBER_TAG} tag, set when
 * the member's work starts ({@link #enter}). Work for a debugged member outside any transaction (scheduled, event
 * driven) runs in a {@value #BACKGROUND_OPERATION} transaction of its own. Unless the member's secrets are revealed,
 * the request data of what is sent is masked as the recorded exchanges are.
 */
@Configuration
public class DebugSentryConfig implements InitializingBean, DisposableBean {

    /** Tag (and log attribute) naming the debugged member a report is for. */
    public static final String MEMBER_TAG = "catapult.debug_member";
    /** Name and operation of the transaction wrapping a debugged member's work done outside a request. */
    public static final String BACKGROUND_OPERATION = "debug.background";
    /** Data key the Sentry HTTP client spans keep the query string of an outgoing call in. */
    private static final String SPAN_QUERY = "http.query";

    private final DebugMemberRegistry registry;
    private final DebugRedactor redactor;

    public DebugSentryConfig(DebugMemberRegistry registry, DebugRedactor redactor) {
        this.registry = registry;
        this.redactor = redactor;
    }

    /** {@code catapult-api@<commit>}, as catapult-web names its own release. */
    @Bean
    Sentry.OptionsConfiguration<SentryOptions> sentryApiRelease(Optional<BuildProperties> build) {
        return options -> build.map(properties -> properties.get("git.commit"))
                .filter(commit -> !commit.isBlank() && !commit.equals("unknown"))
                .ifPresent(commit -> options.setRelease("catapult-api@" + commit));
    }

    /**
     * A trace catapult-web sampled goes on (whoever it is for: what is not a debugged member's is dropped before
     * sending); otherwise only a debugged member's work is traced.
     */
    @Bean
    SentryOptions.TracesSamplerCallback tracesSampler() {
        return context -> {
            if (Boolean.TRUE.equals(context.getTransactionContext().getParentSampled())) {
                return 1.0;
            }
            CustomSamplingContext custom = context.getCustomSamplingContext();
            Object member = custom == null ? null : custom.get(MEMBER_TAG);
            boolean debugged = currentMode().isPresent()
                    || (member instanceof UUID id && registry.modeOf(id).isPresent());
            return debugged ? 1.0 : 0.0;
        };
    }

    /** Errors, the logback ones included: only a debugged member's, as that member. */
    @Bean
    SentryOptions.BeforeSendCallback beforeSend() {
        return (event, hint) -> modeFor(event.getTag(MEMBER_TAG))
                .map(mode -> decorate(event, mode))
                .orElse(null);
    }

    @Bean
    SentryOptions.BeforeSendTransactionCallback beforeSendTransaction() {
        return (transaction, hint) -> modeFor(transaction.getTag(MEMBER_TAG))
                .map(mode -> {
                    if (!mode.revealSecrets()) {
                        transaction.getSpans().forEach(this::maskSpan);
                    }
                    return decorate(transaction, mode);
                })
                .orElse(null);
    }

    @Bean
    SentryOptions.Logs.BeforeSendLogCallback beforeSendLog() {
        return log -> currentMode()
                .map(mode -> {
                    if (log.getAttributes() != null) {
                        log.getAttributes().remove("user.name");
                        log.getAttributes().remove("user.email");
                    }
                    log.setAttribute("user.id", text(mode.userId()));
                    log.setAttribute(MEMBER_TAG, text(mode.userId()));
                    return log;
                })
                .orElse(null);
    }

    @Bean
    SentryOptions.Metrics.BeforeSendMetricCallback beforeSendMetric() {
        return (metric, hint) -> currentMode()
                .map(mode -> {
                    Map<String, SentryLogEventAttributeValue> attributes = metric.getAttributes() == null
                            ? new HashMap<>() : new HashMap<>(metric.getAttributes());
                    attributes.put(MEMBER_TAG, text(mode.userId()));
                    metric.setAttributes(attributes);
                    return metric;
                })
                .orElse(null);
    }

    /** The trace of the current span, so that a recorded exchange links to it; null without Sentry or a span. */
    @Bean
    TraceIdSource sentryTraceIds() {
        return () -> {
            if (!Sentry.isEnabled()) {
                return null;
            }
            ISpan span = Sentry.getSpan();
            return span == null ? null : span.getSpanContext().getTraceId().toString();
        };
    }

    @Override
    public void afterPropertiesSet() {
        DebugContext.setEnterHook(this::enter);
    }

    @Override
    public void destroy() {
        DebugContext.setEnterHook(null);
    }

    /**
     * Called as {@code member}'s work starts on this thread. A debugged member's work joins the transaction under
     * way (tagged with the member unless it already is with someone), or else gets a {@value #BACKGROUND_OPERATION}
     * one, on scopes of its own so that no other thread sees it, finished when the work is. The recorder's own
     * threads are left alone: what they do is the recording, not the member's work.
     */
    AutoCloseable enter(UUID member) {
        if (!Sentry.isEnabled() || registry.modeOf(member).isEmpty()
                || Thread.currentThread().getName().startsWith(DebugExchangeRecorder.THREAD_NAME_PREFIX)) {
            return null;
        }
        ITransaction active = Sentry.getCurrentScopes().getTransaction();
        if (active != null) {
            if (active.getTag(MEMBER_TAG) == null) {
                active.setTag(MEMBER_TAG, member.toString());
            }
            return null;
        }
        IScopes scopes = Sentry.forkedScopes(BACKGROUND_OPERATION);
        ISentryLifecycleToken current = scopes.makeCurrent();
        try {
            CustomSamplingContext sampling = new CustomSamplingContext();
            sampling.set(MEMBER_TAG, member);
            TransactionOptions options = new TransactionOptions();
            options.setBindToScope(true);
            options.setCustomSamplingContext(sampling);
            ITransaction transaction = scopes.startTransaction(
                    new TransactionContext(BACKGROUND_OPERATION, BACKGROUND_OPERATION), options);
            transaction.setTag(MEMBER_TAG, member.toString());
            return () -> {
                try {
                    transaction.finish();
                } finally {
                    current.close();
                }
            };
        } catch (RuntimeException e) {
            current.close();
            throw e;
        }
    }

    private Optional<DebugMode> currentMode() {
        return DebugContext.subject().flatMap(registry::modeOf);
    }

    /** The current subject's mode, else that of the member {@code tagged} names; empty unless in debug mode. */
    private Optional<DebugMode> modeFor(String tagged) {
        Optional<DebugMode> current = currentMode();
        if (current.isPresent() || tagged == null) {
            return current;
        }
        try {
            return registry.modeOf(UUID.fromString(tagged));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Names the member (and no one else: on an admin's request about a member, the admin is dropped). */
    private <T extends SentryBaseEvent> T decorate(T event, DebugMode mode) {
        User user = new User();
        user.setId(mode.userId().toString());
        event.setUser(user);
        event.setTag(MEMBER_TAG, mode.userId().toString());
        if (!mode.revealSecrets()) {
            maskRequest(event.getRequest());
        }
        return event;
    }

    private void maskRequest(Request request) {
        if (request == null) {
            return;
        }
        if (request.getHeaders() != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            request.getHeaders().forEach((name, value) ->
                    headers.put(name, redactor.isSecretHeader(name) ? DebugRedactor.MASK : value));
            request.setHeaders(headers);
        }
        if (request.getCookies() != null) {
            request.setCookies(DebugRedactor.MASK);
        }
        request.setQueryString(redactor.query(request.getQueryString(), false));
    }

    private void maskSpan(SentrySpan span) {
        if (span.getData() != null && span.getData().get(SPAN_QUERY) instanceof String query) {
            Map<String, Object> data = new HashMap<>(span.getData());
            data.put(SPAN_QUERY, redactor.query(query, false));
            span.setData(data);
        }
    }

    private static SentryLogEventAttributeValue text(UUID value) {
        return new SentryLogEventAttributeValue(SentryAttributeType.STRING, value.toString());
    }
}
