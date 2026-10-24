package fr.enimaloc.catapult.monitoring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.debug.DebugRedactor;
import fr.enimaloc.catapult.service.debug.TraceIdSource;
import io.sentry.CustomSamplingContext;
import io.sentry.Hint;
import io.sentry.ISentryLifecycleToken;
import io.sentry.ITransaction;
import io.sentry.SamplingContext;
import io.sentry.Sentry;
import io.sentry.SentryDate;
import io.sentry.SentryEvent;
import io.sentry.SentryItemType;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogLevel;
import io.sentry.SentryMetricsEvent;
import io.sentry.SentryNanotimeDate;
import io.sentry.SentryOptions;
import io.sentry.TransactionContext;
import io.sentry.TransactionOptions;
import io.sentry.logback.SentryAppender;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.TransactionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.sentry.IScopes;
import io.sentry.spring.boot4.SentryAutoConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugSentryConfigTest {

    private final UUID debugged = UUID.randomUUID();
    private final UUID revealed = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private DebugSentryConfig config;
    private RecordingSentry sentry;

    @BeforeEach
    void setUp() {
        when(registry.modeOf(any())).thenReturn(Optional.empty());
        when(registry.modeOf(debugged)).thenReturn(Optional.of(new DebugMode(debugged, false)));
        when(registry.modeOf(revealed)).thenReturn(Optional.of(new DebugMode(revealed, true)));
        config = new DebugSentryConfig(registry, new DebugRedactor());
    }

    @AfterEach
    void tearDown() {
        config.destroy();
        DebugContext.setEnterHook(null);
        if (sentry != null) {
            sentry.close();
        }
    }

    /** A Sentry SDK set up as the starter would, with the config's beans, its hook installed. */
    private RecordingSentry startSentry() {
        sentry = RecordingSentry.init(options -> {
            options.setTracesSampler(config.tracesSampler());
            options.setBeforeSend(config.beforeSend());
            options.setBeforeSendTransaction(config.beforeSendTransaction());
            options.getLogs().setBeforeSend(config.beforeSendLog());
            options.getMetrics().setBeforeSend(config.beforeSendMetric());
        });
        config.afterPropertiesSet();
        return sentry;
    }

    private Double sample(Boolean parentSampled, CustomSamplingContext custom) {
        TransactionContext transaction = new TransactionContext("GET /api/me", "http.server");
        transaction.setParentSampled(parentSampled);
        return config.tracesSampler().sample(new SamplingContext(transaction, custom, 0.5, Map.of()));
    }

    // ---------------------------------------------------------------- sampler

    @Test
    void sampler_zeroWithoutDebuggedSubject() {
        assertThat(sample(null, null)).isZero();
        assertThat(DebugContext.callAs(other, () -> sample(null, null))).isZero();
        assertThat(DebugContext.callAs(other, () -> sample(false, null))).isZero();
    }

    @Test
    void sampler_oneForDebuggedSubject() {
        assertThat(DebugContext.callAs(debugged, () -> sample(null, null))).isEqualTo(1.0);

        CustomSamplingContext custom = new CustomSamplingContext();
        custom.set(DebugSentryConfig.MEMBER_TAG, debugged);
        assertThat(sample(null, custom)).isEqualTo(1.0);
        custom.set(DebugSentryConfig.MEMBER_TAG, other);
        assertThat(sample(null, custom)).isZero();
    }

    @Test
    void sampler_followsSampledParent() {
        assertThat(sample(true, null)).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- errors

    @Test
    void beforeSend_dropsEventsOfNonDebuggedWork() {
        assertThat(config.beforeSend().execute(new SentryEvent(), new Hint())).isNull();
        assertThat(DebugContext.callAs(other, () -> config.beforeSend().execute(new SentryEvent(), new Hint())))
                .isNull();
    }

    @Test
    void beforeSend_keepsADebuggedMembersEvent_asThatMember() {
        SentryEvent event = new SentryEvent();
        io.sentry.protocol.User admin = new io.sentry.protocol.User();
        admin.setUsername("the-admin");
        event.setUser(admin);

        SentryEvent kept = DebugContext.callAs(debugged, () -> config.beforeSend().execute(event, new Hint()));

        assertThat(kept).isNotNull();
        assertThat(kept.getUser().getId()).isEqualTo(debugged.toString());
        assertThat(kept.getUser().getUsername()).isNull();
        assertThat(kept.getTag(DebugSentryConfig.MEMBER_TAG)).isEqualTo(debugged.toString());
    }

    @Test
    void beforeSend_masksTheRequestSecrets_unlessTheyAreRevealed() {
        SentryEvent masked = DebugContext.callAs(debugged,
                () -> config.beforeSend().execute(eventWithRequest(), new Hint()));
        assertThat(masked.getRequest().getHeaders())
                .containsEntry("Authorization", "[masqué]")
                .containsEntry("Accept", "application/json");
        assertThat(masked.getRequest().getCookies()).isEqualTo("[masqué]");
        assertThat(masked.getRequest().getQueryString()).isEqualTo("page=2&access_token=[masqué]");

        SentryEvent clear = DebugContext.callAs(revealed,
                () -> config.beforeSend().execute(eventWithRequest(), new Hint()));
        assertThat(clear.getRequest().getHeaders()).containsEntry("Authorization", "Bearer jwt");
        assertThat(clear.getRequest().getCookies()).isEqualTo("SESSION=abc");
        assertThat(clear.getRequest().getQueryString()).isEqualTo("page=2&access_token=tok");
    }

    private static SentryEvent eventWithRequest() {
        Request request = new Request();
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer jwt");
        headers.put("Accept", "application/json");
        request.setHeaders(headers);
        request.setCookies("SESSION=abc");
        request.setQueryString("page=2&access_token=tok");
        SentryEvent event = new SentryEvent();
        event.setRequest(request);
        return event;
    }

    // ---------------------------------------------------------------- transactions

    private static SentryTransaction transaction() {
        return new SentryTransaction("GET /api/me", 0.0, 1.0, List.of(), Map.of(), new TransactionInfo("url"));
    }

    @Test
    void beforeSendTransaction_dropsTransactionsOfNonDebuggedWork() {
        assertThat(config.beforeSendTransaction().execute(transaction(), new Hint())).isNull();
        assertThat(DebugContext.callAs(other,
                () -> config.beforeSendTransaction().execute(transaction(), new Hint()))).isNull();

        SentryTransaction taggedOther = transaction();
        taggedOther.setTag(DebugSentryConfig.MEMBER_TAG, other.toString());
        assertThat(config.beforeSendTransaction().execute(taggedOther, new Hint())).isNull();
    }

    @Test
    void beforeSendTransaction_keepsATransactionTaggedWithADebuggedMember_afterItsWorkIsDone() {
        SentryTransaction tagged = transaction();
        tagged.setTag(DebugSentryConfig.MEMBER_TAG, debugged.toString());

        SentryTransaction kept = config.beforeSendTransaction().execute(tagged, new Hint());

        assertThat(kept).isNotNull();
        assertThat(kept.getUser().getId()).isEqualTo(debugged.toString());
    }

    // ---------------------------------------------------------------- logs and metrics

    private static SentryLogEvent log() {
        SentryDate now = new SentryNanotimeDate();
        return new SentryLogEvent(new SentryId(), now, "something happened", SentryLogLevel.INFO);
    }

    @Test
    void beforeSendLog_dropsLogsOfNonDebuggedWork_andNamesTheDebuggedMember() {
        assertThat(config.beforeSendLog().execute(log())).isNull();
        assertThat(DebugContext.callAs(other, () -> config.beforeSendLog().execute(log()))).isNull();

        SentryLogEvent kept = DebugContext.callAs(debugged, () -> config.beforeSendLog().execute(log()));
        assertThat(kept).isNotNull();
        assertThat(kept.getAttributes().get("user.id").getValue()).isEqualTo(debugged.toString());
        assertThat(kept.getAttributes().get(DebugSentryConfig.MEMBER_TAG).getValue()).isEqualTo(debugged.toString());
    }

    @Test
    void beforeSendMetric_dropsMetricsOfNonDebuggedWork() {
        SentryMetricsEvent metric = new SentryMetricsEvent(new SentryId(), new SentryNanotimeDate(), "m", "counter", 1.0);
        assertThat(config.beforeSendMetric().execute(metric, new Hint())).isNull();
        assertThat(DebugContext.callAs(debugged, () -> config.beforeSendMetric().execute(metric, new Hint())))
                .isNotNull();
    }

    // ---------------------------------------------------------------- end to end, through the SDK

    @Test
    void logback_anErrorLogged_sendsNothing_unlessItIsADebuggedMembersWork() {
        RecordingSentry sentry = startSentry();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        SentryAppender appender = new SentryAppender();
        appender.setContext(context);
        appender.setMinimumEventLevel(Level.ERROR);
        appender.setMinimumBreadcrumbLevel(Level.INFO);
        appender.setMinimumLevel(Level.INFO);
        appender.start();
        Logger logger = context.getLogger("debug-sentry-test");
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            logger.error("boom", new IllegalStateException("no member"));
            DebugContext.runAs(other, () -> {
                logger.info("an info");
                logger.error("boom", new IllegalStateException("not debugged"));
            });
            assertThat(sentry.items()).isEmpty();

            DebugContext.runAs(debugged, () -> logger.error("boom", new IllegalStateException("debugged")));
            assertThat(sentry.items(SentryItemType.Event)).hasSize(1);
            assertThat(sentry.items(SentryItemType.Log)).isNotEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void background_aDebuggedMembersWork_isSentAsADebugBackgroundTransaction() throws Exception {
        RecordingSentry sentry = startSentry();

        DebugContext.runAs(other, () -> { });
        assertThat(sentry.items()).isEmpty();

        AtomicReference<String> traceInside = new AtomicReference<>();
        TraceIdSource traceIds = config.sentryTraceIds();
        DebugContext.runAs(debugged, () -> traceInside.set(traceIds.currentTraceId()));

        var transactions = sentry.items(SentryItemType.Transaction);
        assertThat(transactions).hasSize(1);
        SentryTransaction sent = transactions.getFirst().getTransaction(sentry.options().getSerializer());
        assertThat(sent.getTransaction()).isEqualTo(DebugSentryConfig.BACKGROUND_OPERATION);
        assertThat(sent.getTag(DebugSentryConfig.MEMBER_TAG)).isEqualTo(debugged.toString());
        assertThat(sent.getUser().getId()).isEqualTo(debugged.toString());
        assertThat(traceInside.get()).isEqualTo(sent.getContexts().getTrace().getTraceId().toString());
        assertThat(traceIds.currentTraceId()).isNull();
    }

    @Test
    void background_anActiveTransaction_isKeptAndTaggedWithTheMember_insteadOfStartingAnother() throws Exception {
        RecordingSentry sentry = startSentry();
        TransactionContext request = new TransactionContext("GET /api/me", "http.server");
        request.setParentSampled(true);
        TransactionOptions options = new TransactionOptions();
        options.setBindToScope(true);
        try (ISentryLifecycleToken ignored = Sentry.forkedScopes("request").makeCurrent()) {
            ITransaction tx = Sentry.startTransaction(request, options);
            DebugContext.runAs(debugged, () -> { });
            tx.finish();
        }

        var transactions = sentry.items(SentryItemType.Transaction);
        assertThat(transactions).hasSize(1);
        SentryTransaction sent = transactions.getFirst().getTransaction(sentry.options().getSerializer());
        assertThat(sent.getTransaction()).isEqualTo("GET /api/me");
        assertThat(sent.getTag(DebugSentryConfig.MEMBER_TAG)).isEqualTo(debugged.toString());
    }

    @Test
    void anActiveTransactionOfNonDebuggedWork_isNotSent_evenWhenItsParentWasSampled() {
        RecordingSentry sentry = startSentry();
        TransactionContext request = new TransactionContext("GET /api/me", "http.server");
        request.setParentSampled(true);
        TransactionOptions options = new TransactionOptions();
        options.setBindToScope(true);
        try (ISentryLifecycleToken ignored = Sentry.forkedScopes("request").makeCurrent()) {
            ITransaction tx = Sentry.startTransaction(request, options);
            DebugContext.runAs(other, () -> { });
            tx.finish();
        }
        assertThat(sentry.items()).isEmpty();
    }

    @Test
    void afterShutdown_theHookIsGone() {
        RecordingSentry sentry = startSentry();
        config.destroy();
        DebugContext.runAs(debugged, () -> { });
        assertThat(sentry.items()).isEmpty();
    }

    @Test
    void withoutDsn_sentryStaysOff_andTheWorkStillRuns() {
        config.afterPropertiesSet();
        AtomicReference<String> trace = new AtomicReference<>("unset");
        DebugContext.runAs(debugged, () -> trace.set(config.sentryTraceIds().currentTraceId()));
        assertThat(Sentry.isEnabled()).isFalse();
        assertThat(trace.get()).isNull();
    }

    // ---------------------------------------------------------------- with the Spring Boot starter

    private ApplicationContextRunner starter() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration.class))
                .withUserConfiguration(DebugSentryConfig.class)
                .withBean(DebugMemberRegistry.class, () -> registry)
                .withBean(DebugRedactor.class, DebugRedactor::new);
    }

    @Test
    void starter_withAnEmptyDsn_bootsWithSentryOff() {
        starter().withPropertyValues("sentry.dsn=", "sentry.logs.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(Sentry.isEnabled()).isFalse();
            assertThat(context.getBean(TraceIdSource.class).currentTraceId()).isNull();
        });
    }

    @Test
    void starter_withADsn_sendsThroughTheDebugGates() {
        starter().withPropertyValues("sentry.dsn=https://key@sentry.invalid/1", "sentry.logs.enabled=true",
                        "sentry.send-client-reports=false")
                .run(context -> {
                    try {
                        assertThat(context).hasNotFailed();
                        SentryOptions options = context.getBean(IScopes.class).getOptions();
                        assertThat(options.getBeforeSend()).isNotNull();
                        assertThat(options.getBeforeSendTransaction()).isNotNull();
                        assertThat(options.getLogs().getBeforeSend()).isNotNull();
                        assertThat(options.getMetrics().getBeforeSend()).isNotNull();
                        assertThat(options.getTracesSampler()).isNotNull();
                        assertThat(options.isSendClientReports()).isFalse();
                        assertThat(options.getBeforeSend().execute(new SentryEvent(), new Hint())).isNull();
                    } finally {
                        Sentry.close();
                    }
                });
    }
}
