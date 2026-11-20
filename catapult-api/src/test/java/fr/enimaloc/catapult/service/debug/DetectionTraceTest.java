package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DetectionTraceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);

    @AfterEach
    void unbound() {
        assertThat(DetectionTrace.active()).isFalse();
    }

    private void inDebug() {
        when(registry.modeOf(USER)).thenReturn(Optional.of(new DebugMode(USER, false)));
    }

    /** The one exchange recorded for {@link #USER}, and its JSON body. */
    static RawExchange recorded(DebugExchangeRecorder recorder, UUID userId) {
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(userId), eq(Direction.OUT), raw.capture(), eq("detection.cycle"), eq(false),
                eq("detection"));
        return raw.getValue();
    }

    static JsonNode body(RawExchange raw) {
        return JSON.readTree(new String(raw.requestBody(), StandardCharsets.UTF_8));
    }

    @Test
    void recordsOneCycleExchangeWhenClosed() {
        inDebug();

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            assertThat(DetectionTrace.active()).isTrue();
            DetectionTrace.provider("BATTLENET", "none", "no presence received since the session opened",
                    Map.of("session", "READY"));
            DetectionTrace.outcome("unchanged", Map.of("game", "DOOM"));
            DetectionTrace.binding(Map.of("guard", "NO_CUSTOM_BINDING"));
        }

        RawExchange raw = recorded(recorder, USER);
        assertThat(raw.method()).isEqualTo("CYCLE");
        assertThat(raw.uri()).isEqualTo(URI.create("catapult://detection"));
        assertThat(raw.error()).isNull();
        assertThat(raw.durationMs()).isGreaterThanOrEqualTo(0);
        JsonNode body = body(raw);
        assertThat(body.at("/providers/0/provider").asString()).isEqualTo("BATTLENET");
        assertThat(body.at("/providers/0/result").asString()).isEqualTo("none");
        assertThat(body.at("/providers/0/reason").asString())
                .isEqualTo("no presence received since the session opened");
        assertThat(body.at("/providers/0/details/session").asString()).isEqualTo("READY");
        assertThat(body.at("/outcome/outcome").asString()).isEqualTo("unchanged");
        assertThat(body.at("/outcome/game").asString()).isEqualTo("DOOM");
        assertThat(body.at("/binding/guard").asString()).isEqualTo("NO_CUSTOM_BINDING");
    }

    @Test
    void omitsEmptyFields() {
        inDebug();

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            DetectionTrace.provider("STEAM", "disabled", null, null);
        }

        JsonNode body = body(recorded(recorder, USER));
        assertThat(body.at("/providers/0").has("reason")).isFalse();
        assertThat(body.at("/providers/0").has("details")).isFalse();
        assertThat(body.has("outcome")).isFalse();
        assertThat(body.has("binding")).isFalse();
    }

    @Test
    void aTimeoutOutcomeIsTheExchangesError() {
        inDebug();

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            DetectionTrace.outcome("timeout", Map.of("message", "Steam timed out"));
        }

        assertThat(recorded(recorder, USER).error()).isEqualTo("Steam timed out");
    }

    @Test
    void noOpWhenNotInDebug() {
        when(registry.modeOf(USER)).thenReturn(Optional.empty());

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            assertThat(DetectionTrace.active()).isFalse();
            DetectionTrace.provider("STEAM", "none", "x", Map.of());
            DetectionTrace.reason("y");
            DetectionTrace.detail("k", "v");
            DetectionTrace.outcome("nothing", null);
            DetectionTrace.binding(Map.of("id", "1"));
        }

        verifyNoInteractions(recorder);
    }

    @Test
    void noOpWithoutRegistryOrRecorder() {
        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(null, null, USER)) {
            assertThat(DetectionTrace.active()).isFalse();
        }
    }

    @Test
    void noOpWhenTheRegistryFails() {
        when(registry.modeOf(USER)).thenThrow(new IllegalStateException("boom"));

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            assertThat(DetectionTrace.active()).isFalse();
        }

        verifyNoInteractions(recorder);
    }

    @Test
    void closeUnbindsTheThreadEvenWhenRecordingFails() {
        inDebug();
        doThrow(new IllegalStateException("boom")).when(recorder)
                .record(any(), any(), any(), anyString(), anyBoolean(), anyString());

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            DetectionTrace.provider("STEAM", "none", null, null);
        }

        assertThat(DetectionTrace.active()).isFalse();
        DetectionTrace.reason("after");
        DetectionTrace.provider("XBOX", "none", null, null);
        verify(recorder).record(any(), any(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void reasonIsConsumedByTheNextProviderEntry() {
        inDebug();

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            DetectionTrace.reason("no linked token");
            DetectionTrace.detail("link", "PENDING");
            DetectionTrace.provider("XBOX", "none", null, null);
            DetectionTrace.provider("STEAM", "none", null, null);
        }

        JsonNode body = body(recorded(recorder, USER));
        assertThat(body.at("/providers/0/reason").asString()).isEqualTo("no linked token");
        assertThat(body.at("/providers/0/details/link").asString()).isEqualTo("PENDING");
        assertThat(body.at("/providers/1").has("reason")).isFalse();
        assertThat(body.at("/providers/1").has("details")).isFalse();
    }

    @Test
    void anExplicitReasonWinsOverTheGettersOne() {
        inDebug();

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            DetectionTrace.reason("half way");
            DetectionTrace.provider("XBOX", "error", "connection reset", null);
        }

        assertThat(body(recorded(recorder, USER)).at("/providers/0/reason").asString())
                .isEqualTo("connection reset");
    }

    @Test
    void detailValuesAreWrittenAsPlainValues() {
        inDebug();
        enum Link { ACCEPTED }

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            DetectionTrace.detail("link", Link.ACCEPTED);
            DetectionTrace.detail("count", 3);
            DetectionTrace.detail("skipped", null);
            DetectionTrace.provider("BATTLENET", "none", null, null);
        }

        JsonNode details = body(recorded(recorder, USER)).at("/providers/0/details");
        assertThat(details.path("link").asString()).isEqualTo("ACCEPTED");
        assertThat(details.path("count").asInt()).isEqualTo(3);
        assertThat(details.has("skipped")).isFalse();
    }

    @Test
    void bindingDetailsAccumulate() {
        inDebug();

        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, USER)) {
            DetectionTrace.binding(Map.of("guard", "NO_CUSTOM_BINDING"));
            DetectionTrace.binding(Map.of("rule", "GAME"));
        }

        JsonNode binding = body(recorded(recorder, USER)).path("binding");
        assertThat(binding.path("guard").asString()).isEqualTo("NO_CUSTOM_BINDING");
        assertThat(binding.path("rule").asString()).isEqualTo("GAME");
    }

    @Test
    void detailsSkipsNullValues() {
        assertThat(DetectionTrace.details("a", 1, "b", null)).containsExactly(Map.entry("a", 1));
    }

    @Test
    void nothingIsRecordedWhenTheMemberLeftDebugBeforeTheOpen() {
        when(registry.modeOf(USER)).thenReturn(Optional.empty());

        DetectionTrace.openIfDebugged(registry, recorder, USER).close();

        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean(), any());
    }
}
