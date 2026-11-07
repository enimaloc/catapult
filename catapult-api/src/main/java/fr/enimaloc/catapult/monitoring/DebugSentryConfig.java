package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.debug.DebugRedactor;
import fr.enimaloc.catapult.service.debug.OtherMemberMasker;
import fr.enimaloc.catapult.service.debug.TraceIdSource;
import io.sentry.Breadcrumb;
import io.sentry.CustomSamplingContext;
import io.sentry.IScope;
import io.sentry.IScopes;
import io.sentry.ISentryLifecycleToken;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.ScopeType;
import io.sentry.Sentry;
import io.sentry.SentryAttributeType;
import io.sentry.SentryBaseEvent;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryOptions;
import io.sentry.SpanId;
import io.sentry.TransactionContext;
import io.sentry.TransactionOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
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
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * catapult-api reports to Sentry only the work of a member in debug mode: its traces, errors, logs and breadcrumbs,
 * under that member, and nothing at all for anyone else (with no DSN, Sentry stays off and none of this does
 * anything).
 * <p>
 * Whose work it is comes from {@link DebugContext#subject()} on the thread that captures an error, a log or a
 * breadcrumb; a transaction, which is sent once that work is over, carries the member in its {@value #MEMBER_TAG}
 * tag, set when the member's work starts ({@link #enter}). A debugged member's work outside any sampled transaction
 * (scheduled, event driven, or a request that came without a sampled trace) runs in a transaction of its own.
 * <p>
 * Breadcrumbs are kept only for a debugged member's work and marked with that member: an event carries only its own
 * member's, never those another member's work left on a pooled thread's scopes. What is sent is scrubbed as the
 * recorded exchanges are: other members' identifiers always, secrets unless the member's are revealed.
 */
@Configuration
public class DebugSentryConfig implements InitializingBean, DisposableBean {

    /** Tag (and log attribute, breadcrumb data) naming the debugged member a report is for. */
    public static final String MEMBER_TAG = "catapult.debug_member";
    /** Name and operation of the transaction wrapping a debugged member's work done outside a request. */
    public static final String BACKGROUND_OPERATION = "debug.background";
    /** Log attributes naming the member, left as they are by the scrubbing. */
    private static final Set<String> MEMBER_ATTRIBUTES = Set.of("user.id", MEMBER_TAG);

    private final DebugMemberRegistry registry;
    private final DebugRedactor redactor;
    private final OtherMemberMasker masker;

    public DebugSentryConfig(DebugMemberRegistry registry, DebugRedactor redactor, OtherMemberMasker masker) {
        this.registry = registry;
        this.redactor = redactor;
        this.masker = masker;
    }

    /** {@code catapult-api@<commit>}, as catapult-web names its own release. */
    @Bean
    Sentry.OptionsConfiguration<SentryOptions> sentryApiRelease(Optional<BuildProperties> build) {
        return options -> build.map(properties -> properties.get("git.commit"))
                .filter(commit -> !commit.isBlank() && !commit.equals("unknown"))
                .ifPresent(commit -> options.setRelease("catapult-api@" + commit));
    }

    /**
     * Only a debugged member's work is traced, whether or not catapult-web sampled the trace it continues: a request
     * transaction starts before its member is known, so it is not sampled, and the debugged member's work then runs
     * in a transaction of its own in that same trace ({@link #enter}). No other member's request builds spans.
     */
    @Bean
    SentryOptions.TracesSamplerCallback tracesSampler() {
        return context -> {
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
                .map(mode -> {
                    scrubTexts(event, mode);
                    return decorate(event, mode);
                })
                .orElse(null);
    }

    @Bean
    SentryOptions.BeforeSendTransactionCallback beforeSendTransaction() {
        return (transaction, hint) -> modeFor(transaction.getTag(MEMBER_TAG))
                .map(mode -> {
                    scrubSpans(transaction.getSpans(), mode);
                    return decorate(transaction, mode);
                })
                .orElse(null);
    }

    @Bean
    SentryOptions.Logs.BeforeSendLogCallback beforeSendLog() {
        return log -> currentMode()
                .map(mode -> {
                    log.setBody(scrub(log.getBody(), mode));
                    if (log.getAttributes() != null) {
                        log.getAttributes().remove("user.name");
                        log.getAttributes().remove("user.email");
                        log.getAttributes().replaceAll((name, value) ->
                                MEMBER_ATTRIBUTES.contains(name) || !(value.getValue() instanceof String text)
                                        ? value
                                        : new SentryLogEventAttributeValue(value.getType(), scrub(text, mode)));
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

    /**
     * Breadcrumbs (logback's, the HTTP client's) are kept only for a debugged member's work, scrubbed and marked with
     * that member so that only its own events carry them.
     */
    @Bean
    SentryOptions.BeforeBreadcrumbCallback beforeBreadcrumb() {
        return (breadcrumb, hint) -> currentMode()
                .map(mode -> {
                    scrub(breadcrumb, mode);
                    breadcrumb.setData(MEMBER_TAG, mode.userId().toString());
                    return breadcrumb;
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
     * Called as {@code member}'s work starts on this thread. A debugged member's work joins the sampled transaction
     * under way (tagged with the member unless it already is with someone). Otherwise it gets a transaction of its
     * own, sampled for the member: a child of the unsampled one under way, in its trace and under its name (a request
     * that came without a sampled trace), or else a {@value #BACKGROUND_OPERATION} one. That transaction runs on
     * forked scopes, emptied of the breadcrumbs earlier work left on this thread, which no other thread sees and which
     * are dropped when the work ends. The recorder's own threads are left alone: what they do is the recording, not
     * the member's work.
     */
    AutoCloseable enter(UUID member) {
        if (!Sentry.isEnabled() || registry.modeOf(member).isEmpty()
                || Thread.currentThread().getName().startsWith(DebugExchangeRecorder.THREAD_NAME_PREFIX)) {
            return null;
        }
        ITransaction active = Sentry.getCurrentScopes().getTransaction();
        if (active != null && Boolean.TRUE.equals(active.isSampled())) {
            if (active.getTag(MEMBER_TAG) == null) {
                active.setTag(MEMBER_TAG, member.toString());
            }
            return null;
        }
        IScopes scopes = Sentry.forkedScopes(BACKGROUND_OPERATION);
        ISentryLifecycleToken current = scopes.makeCurrent();
        try {
            scopes.configureScope(ScopeType.CURRENT, IScope::clearBreadcrumbs);
            scopes.configureScope(ScopeType.ISOLATION, IScope::clearBreadcrumbs);
            CustomSamplingContext sampling = new CustomSamplingContext();
            sampling.set(MEMBER_TAG, member);
            TransactionOptions options = new TransactionOptions();
            options.setBindToScope(true);
            options.setCustomSamplingContext(sampling);
            ITransaction transaction = scopes.startTransaction(transactionContext(active), options);
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

    /** The member's own transaction: in the trace of the unsampled {@code active} one when there is one. */
    private static TransactionContext transactionContext(ITransaction active) {
        if (active == null) {
            return new TransactionContext(BACKGROUND_OPERATION, BACKGROUND_OPERATION);
        }
        TransactionContext context = new TransactionContext(active.getSpanContext().getTraceId(), new SpanId(),
                active.getSpanContext().getSpanId(), null, null);
        context.setName(active.getName());
        context.setOperation(Objects.requireNonNullElse(active.getOperation(), BACKGROUND_OPERATION));
        return context;
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

    /**
     * Names the member (and no one else: on an admin's request about a member, the admin is dropped), keeps only the
     * breadcrumbs of that member's work, and masks the request's secrets unless revealed.
     */
    private <T extends SentryBaseEvent> T decorate(T event, DebugMode mode) {
        User user = new User();
        user.setId(mode.userId().toString());
        event.setUser(user);
        event.setTag(MEMBER_TAG, mode.userId().toString());
        if (event.getBreadcrumbs() != null) {
            String member = mode.userId().toString();
            event.setBreadcrumbs(event.getBreadcrumbs().stream()
                    .filter(b -> member.equals(b.getData(MEMBER_TAG)))
                    .toList());
        }
        scrubRequest(event.getRequest(), mode);
        return event;
    }

    /** Other members' identifiers always; secrets in free text (a URL, a form, JSON) unless revealed. */
    private String scrub(String text, DebugMode mode) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String unsecret = mode.revealSecrets() ? text : redactor.text(text, false);
        return masker.mask(unsecret, mode.userId());
    }

    private void scrub(Breadcrumb breadcrumb, DebugMode mode) {
        breadcrumb.setMessage(scrub(breadcrumb.getMessage(), mode));
        for (Map.Entry<String, Object> data : new HashMap<>(breadcrumb.getData()).entrySet()) {
            if (data.getValue() instanceof String text) {
                breadcrumb.setData(data.getKey(), scrub(text, mode));
            }
        }
    }

    private void scrubTexts(SentryEvent event, DebugMode mode) {
        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(scrub(message.getMessage(), mode));
            message.setFormatted(scrub(message.getFormatted(), mode));
            if (message.getParams() != null) {
                message.setParams(message.getParams().stream().map(p -> scrub(p, mode)).toList());
            }
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(scrub(exception.getValue(), mode));
            }
        }
    }

    /** Span descriptions ({@code GET <url>}) and their string data ({@code url}, {@code http.query}…). */
    private void scrubSpans(List<SentrySpan> spans, DebugMode mode) {
        for (ListIterator<SentrySpan> it = spans.listIterator(); it.hasNext(); ) {
            SentrySpan span = it.next();
            Map<String, Object> data = span.getData() == null ? null : new HashMap<>(span.getData());
            if (data != null) {
                data.replaceAll((key, value) -> value instanceof String text ? scrub(text, mode) : value);
            }
            SentrySpan scrubbed = new SentrySpan(span.getStartTimestamp(), span.getTimestamp(), span.getTraceId(),
                    span.getSpanId(), span.getParentSpanId(), span.getOp(), scrub(span.getDescription(), mode),
                    span.getStatus(), span.getOrigin(), span.getTags(), span.getMeasurements(), data);
            scrubbed.setUnknown(span.getUnknown());
            it.set(scrubbed);
        }
    }

    private void scrubRequest(Request request, DebugMode mode) {
        if (request == null) {
            return;
        }
        if (!mode.revealSecrets()) {
            if (request.getHeaders() != null) {
                Map<String, String> headers = new LinkedHashMap<>();
                request.getHeaders().forEach((name, value) ->
                        headers.put(name, redactor.isSecretHeader(name)
                                ? DebugRedactor.MASK : redactor.text(value, false)));
                request.setHeaders(headers);
            }
            if (request.getCookies() != null) {
                request.setCookies(DebugRedactor.MASK);
            }
            request.setQueryString(redactor.query(request.getQueryString(), false));
        }
        request.setUrl(masker.mask(request.getUrl(), mode.userId()));
        request.setQueryString(masker.mask(request.getQueryString(), mode.userId()));
    }

    private static SentryLogEventAttributeValue text(UUID value) {
        return new SentryLogEventAttributeValue(SentryAttributeType.STRING, value.toString());
    }
}
