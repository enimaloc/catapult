package fr.enimaloc.catapult.api.internal;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.ClientDebugIngestService;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetAccess;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiInternalClientDebugControllerTest {

    private static final String BATCH = """
            {"source":"page","sentAt":"2026-11-20T12:00:00Z","entries":[
              {"service":"obs","direction":"OUT","operation":"GetSceneList","at":"2026-11-20T12:00:00Z","status":100}
            ]}""";

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final TwitchatWidgetAccess widgets = mock(TwitchatWidgetAccess.class);
    private final ClientDebugIngestService service = new ClientDebugIngestService(registry, recorder);
    private final ApiInternalClientDebugController controller = new ApiInternalClientDebugController(service, widgets);
    private final UUID member = UUID.randomUUID();

    private static MockHttpServletRequest body(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void assertRefused(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    private void inDebug() {
        when(registry.modeOf(member)).thenReturn(Optional.of(new DebugMode(member, false)));
    }

    @Test
    void returns204WhenRecorded() {
        inDebug();

        assertThat(controller.record(member, body(BATCH)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(recorder).record(eq(member), eq(Direction.OUT), any(RawExchange.class), eq("GetSceneList"), eq(false),
                eq("obs"));
    }

    @Test
    void returns409WhenNotInDebug() {
        when(registry.modeOf(member)).thenReturn(Optional.empty());

        assertRefused(() -> controller.record(member, body(BATCH)), HttpStatus.CONFLICT);
        verifyNoInteractions(recorder);
    }

    @Test
    void returns400ForAnOversizedBatch() {
        inDebug();
        String huge = "{\"source\":\"" + "x".repeat(ClientDebugIngestService.MAX_BYTES) + "\",\"entries\":[]}";

        assertRefused(() -> controller.record(member, body(huge)), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(recorder);
    }

    @Test
    void returns400ForAnUnreadableBody() {
        inDebug();

        assertRefused(() -> controller.record(member, body("{oops")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void returns400WhenTheBodyCannotBeRead() throws IOException {
        inDebug();
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletInputStream in = mock(ServletInputStream.class);
        when(request.getInputStream()).thenReturn(in);
        when(in.readNBytes(anyInt())).thenThrow(new IOException("reset"));

        assertRefused(() -> controller.record(member, request), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(recorder);
    }

    @Test
    void anExtremeTimestampIsSkippedNotA500() {
        inDebug();
        String json = "{\"sentAt\":\"" + Instant.now().minusSeconds(3600) + "\",\"entries\":["
                + "{\"service\":\"obs\",\"direction\":\"IN\",\"at\":\"+1000000000-12-31T23:59:59.999999999Z\"}]}";

        assertThat(controller.record(member, body(json)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(recorder);
    }

    @Test
    void theBodyIsNotReadWhenNotInDebug() throws IOException {
        when(registry.modeOf(member)).thenReturn(Optional.empty());
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertRefused(() -> controller.record(member, request), HttpStatus.CONFLICT);
        verify(request, never()).getInputStream();
    }

    @Test
    void theBodyIsNotReadForAnUnknownWidget() throws IOException {
        UUID widgetId = UUID.randomUUID();
        when(widgets.owner(widgetId)).thenReturn(Optional.empty());
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertRefused(() -> controller.recordFromWidget(widgetId, request), HttpStatus.NOT_FOUND);
        verify(request, never()).getInputStream();
    }

    @Test
    void widgetReturns409WhenItsOwnerIsNotInDebug() {
        UUID widgetId = UUID.randomUUID();
        when(widgets.owner(widgetId)).thenReturn(Optional.of(member));
        when(registry.modeOf(member)).thenReturn(Optional.empty());

        assertRefused(() -> controller.recordFromWidget(widgetId, body(BATCH)), HttpStatus.CONFLICT);
        verifyNoInteractions(recorder);
    }

    @Test
    void widgetReturns400ForAnUnreadableBody() {
        UUID widgetId = UUID.randomUUID();
        when(widgets.owner(widgetId)).thenReturn(Optional.of(member));
        inDebug();

        assertRefused(() -> controller.recordFromWidget(widgetId, body("[1]")), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(recorder);
    }

    @Test
    void widgetRecordsForItsOwnerAsTheWidget() {
        UUID widgetId = UUID.randomUUID();
        when(widgets.owner(widgetId)).thenReturn(Optional.of(member));
        inDebug();

        assertThat(controller.recordFromWidget(widgetId, body(BATCH)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(member), eq(Direction.OUT), raw.capture(), anyString(), anyBoolean(), anyString());
        assertThat(raw.getValue().requestHeaders().getFirst("source")).as("whatever the body says").isEqualTo("widget");
    }

    @Test
    void widgetWithAnUnknownTokenIs404() {
        UUID widgetId = UUID.randomUUID();
        when(widgets.owner(widgetId)).thenReturn(Optional.empty());

        assertRefused(() -> controller.recordFromWidget(widgetId, body(BATCH)), HttpStatus.NOT_FOUND);
        verifyNoInteractions(recorder);
    }
}
