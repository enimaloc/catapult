package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.monitoring.RecordingSentry;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.protocol.SentryId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DebugSentryBridgeTest {

    private final UUID member = UUID.randomUUID();
    private final List<SentryLogEvent> logs = new CopyOnWriteArrayList<>();
    private final List<Breadcrumb> breadcrumbs = new CopyOnWriteArrayList<>();
    private final DebugSentryBridge bridge = new DebugSentryBridge();
    private RecordingSentry sentry;

    @AfterEach
    void tearDown() {
        if (sentry != null) {
            sentry.close();
        }
    }

    private void startSentry() {
        sentry = RecordingSentry.init(options -> {
            options.getLogs().setBeforeSend(log -> {
                logs.add(log);
                return log;
            });
            options.setBeforeBreadcrumb((breadcrumb, hint) -> {
                breadcrumbs.add(breadcrumb);
                return breadcrumb;
            });
        });
    }

    /** An exchange as the recorder stores it for a member whose secrets are not revealed. */
    private DebugExchange storedMasked(String traceId) {
        DebugRedactor redactor = new DebugRedactor();
        DebugExchange e = new DebugExchange();
        e.setUserId(member);
        e.setOccurredAt(Instant.now());
        e.setDirection(Direction.OUT);
        e.setService("twitch");
        e.setMethod("POST");
        e.setUrl(redactor.url(URI.create("https://id.twitch.tv/oauth2/token?client_secret=s3cr3t"), false));
        e.setRequestBody(redactor.body("refresh_token=r3fr3sh&grant_type=refresh_token".getBytes(StandardCharsets.UTF_8),
                MediaType.APPLICATION_FORM_URLENCODED, false));
        e.setStatus(200);
        e.setResponseBody(redactor.body("{\"access_token\":\"acc3ss\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8),
                MediaType.APPLICATION_JSON, false));
        e.setDurationMs(42);
        e.setFiltered(true);
        e.setTraceId(traceId);
        return e;
    }

    private static Object attribute(SentryLogEvent log, String name) {
        SentryLogEventAttributeValue value = log.getAttributes().get(name);
        return value == null ? null : value.getValue();
    }

    @Test
    void bridge_sendsTheMaskedExchange() {
        startSentry();
        String traceId = new SentryId().toString();

        DebugContext.runAs(member, () -> bridge.onRecorded(member, storedMasked(traceId)));
        Sentry.flush(2000);

        assertThat(logs).hasSize(1);
        SentryLogEvent log = logs.getFirst();
        assertThat(log.getBody()).isEqualTo(
                "debug exchange OUT twitch POST https://id.twitch.tv/oauth2/token?client_secret=[masqué]");
        assertThat(log.getTraceId().toString()).isEqualTo(traceId);
        assertThat(attribute(log, "http.response.status_code")).isEqualTo(200);
        assertThat(attribute(log, "duration_ms")).isEqualTo(42L);
        assertThat(attribute(log, "filtered")).isEqualTo(true);
        assertThat(attribute(log, "user.id")).isEqualTo(member.toString());
        assertThat((String) attribute(log, "request_body")).contains("refresh_token=[masqué]").doesNotContain("r3fr3sh");
        assertThat((String) attribute(log, "response_body")).contains("[masqué]").doesNotContain("acc3ss");
        assertThat(log.getAttributes().values().stream().map(v -> String.valueOf(v.getValue())))
                .noneMatch(v -> v.contains("s3cr3t") || v.contains("r3fr3sh") || v.contains("acc3ss"));

        assertThat(breadcrumbs).hasSize(1);
        Breadcrumb crumb = breadcrumbs.getFirst();
        assertThat(crumb.getType()).isEqualTo("http");
        assertThat(crumb.getData()).contains(
                Map.entry("url", "https://id.twitch.tv/oauth2/token"),
                Map.entry("http.query", "client_secret=[masqué]"),
                Map.entry("method", "POST"),
                Map.entry("status_code", 200));
    }

    @Test
    void bridge_withoutATraceId_stillSendsTheLog_onATraceOfItsOwn() {
        startSentry();
        DebugContext.runAs(member, () -> bridge.onRecorded(member, storedMasked(null)));
        Sentry.flush(2000);
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getTraceId()).isNotNull();
    }

    @Test
    void bridge_withoutDsn_doesNothing() {
        DebugContext.runAs(member, () -> bridge.onRecorded(member, storedMasked(new SentryId().toString())));
        assertThat(Sentry.isEnabled()).isFalse();
        assertThat(logs).isEmpty();
    }
}
