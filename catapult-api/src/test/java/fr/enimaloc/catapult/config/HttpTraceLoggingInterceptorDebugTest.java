package fr.enimaloc.catapult.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.debug.RawExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What the outbound interceptor hands to the debug recording, and that it never alters the call. */
class HttpTraceLoggingInterceptorDebugTest {

    private final UUID member = UUID.randomUUID();
    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final HttpTraceLoggingInterceptor interceptor = new HttpTraceLoggingInterceptor(registry, recorder);
    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpTraceLoggingInterceptor.class);
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        when(registry.modeOf(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        logger.setLevel(previousLevel);
    }

    private void debugged(UUID id) {
        when(registry.modeOf(id)).thenReturn(Optional.of(new DebugMode(id, false)));
    }

    private static MockClientHttpRequest request() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://api.twitch.tv/helix/users?id=1"));
        request.getHeaders().add("Client-Id", "abc");
        return request;
    }

    private static ClientHttpRequestExecution respondWith(String body) {
        return (request, payload) -> {
            MockClientHttpResponse r = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            r.getHeaders().add("X-Resp", "1");
            return r;
        };
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void debuggedSubject_callIsRecorded() {
        debugged(member);

        DebugContext.runAs(member, () -> call(request(), bytes("{\"q\":1}"), respondWith("{\"ok\":true}")));

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(member), eq(Direction.OUT), raw.capture(), isNull(), eq(false));
        RawExchange r = raw.getValue();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.uri()).hasToString("https://api.twitch.tv/helix/users?id=1");
        assertThat(r.requestHeaders().getFirst("Client-Id")).isEqualTo("abc");
        assertThat(new String(r.requestBody(), StandardCharsets.UTF_8)).isEqualTo("{\"q\":1}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.responseHeaders().getFirst("X-Resp")).isEqualTo("1");
        assertThat(new String(r.responseBody(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        assertThat(r.error()).isNull();
        assertThat(r.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(r.at()).isNotNull();
    }

    @Test
    void subjectNotDebugged_nothingRecorded_andBodyNotRead() {
        AtomicBoolean bodyRead = new AtomicBoolean();
        ClientHttpRequestExecution watched = (request, payload) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
            @Override
            public InputStream getBody() {
                bodyRead.set(true);
                return InputStream.nullInputStream();
            }
        };

        DebugContext.runAs(member, () -> call(request(), new byte[0], watched));

        assertThat(bodyRead).isFalse();
        verifyNoInteractions(recorder);
    }

    @Test
    void noSubject_nothingRecorded() {
        call(request(), new byte[0], respondWith("ok"));

        verifyNoInteractions(recorder);
    }

    @Test
    void callFailure_isRecordedWithError_andRethrown() {
        debugged(member);
        ClientHttpRequestExecution failing = (request, payload) -> { throw new IOException("connection refused"); };

        DebugContext.runAs(member, () -> assertThatThrownBy(() -> interceptor.intercept(request(), new byte[0], failing))
                .isInstanceOf(IOException.class).hasMessage("connection refused"));

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(member), eq(Direction.OUT), raw.capture(), isNull(), eq(false));
        assertThat(raw.getValue().status()).isNull();
        assertThat(raw.getValue().error()).contains("connection refused");
    }

    @Test
    void recorderFailure_neverReachesTheCaller() {
        debugged(member);
        doThrow(new IllegalStateException("boom")).when(recorder).record(any(), any(), any(), any(), eq(false));

        ClientHttpResponse response = DebugContext.callAs(member,
                () -> call(request(), new byte[0], respondWith("ok")));

        assertThat(read(response)).isEqualTo("ok");
    }

    @Test
    void responseBody_stillReadableByTheCaller() {
        debugged(member);

        ClientHttpResponse response = DebugContext.callAs(member,
                () -> call(request(), new byte[0], respondWith("{\"ok\":true}")));

        assertThat(read(response)).isEqualTo("{\"ok\":true}");
    }

    @Test
    void capturing_collectsInsteadOfRecording() {
        var captured = DebugContext.capture(() -> call(request(), new byte[0], respondWith("batch")));

        assertThat(captured.exchanges()).singleElement()
                .satisfies(r -> assertThat(new String(r.responseBody(), StandardCharsets.UTF_8)).isEqualTo("batch"));
        assertThat(read(captured.result())).isEqualTo("batch");
        verifyNoInteractions(recorder);
    }

    @Test
    void capturing_alsoCollectsFailures() {
        ClientHttpRequestExecution failing = (request, payload) -> { throw new IllegalStateException("nope"); };

        var captured = DebugContext.capture(() -> {
            assertThatThrownBy(() -> interceptor.intercept(request(), new byte[0], failing)).hasMessage("nope");
            return null;
        });

        assertThat(captured.exchanges()).singleElement().satisfies(r -> assertThat(r.error()).contains("nope"));
    }

    @Test
    void clientError_isRecorded_andItsBodyStillReadable() {
        debugged(member);
        ClientHttpRequestExecution notFound = (request, payload) ->
                new MockClientHttpResponse("{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8), HttpStatus.NOT_FOUND);

        ClientHttpResponse response = DebugContext.callAs(member, () -> call(request(), new byte[0], notFound));

        assertThat(read(response)).isEqualTo("{\"error\":\"nope\"}");
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(member), eq(Direction.OUT), raw.capture(), isNull(), eq(false));
        assertThat(raw.getValue().status()).isEqualTo(404);
        assertThat(new String(raw.getValue().responseBody(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"nope\"}");
    }

    @Test
    void traceAndDebug_bodyReadOnce_loggedAndRecorded() {
        logger.setLevel(Level.TRACE);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        debugged(member);
        AtomicInteger reads = new AtomicInteger();
        ClientHttpRequestExecution counted = (request, payload) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
            @Override
            public InputStream getBody() {
                reads.incrementAndGet();
                return new ByteArrayInputStream("once".getBytes(StandardCharsets.UTF_8));
            }
        };
        try {
            ClientHttpResponse response = DebugContext.callAs(member, () -> call(request(), new byte[0], counted));

            assertThat(read(response)).isEqualTo("once");
            assertThat(reads).hasValue(1);
            assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getFormattedMessage()).contains("body=once"));
            ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
            verify(recorder).record(eq(member), eq(Direction.OUT), raw.capture(), isNull(), eq(false));
            assertThat(new String(raw.getValue().responseBody(), StandardCharsets.UTF_8)).isEqualTo("once");
        } finally {
            logger.detachAppender(logs);
        }
    }

    private ClientHttpResponse call(MockClientHttpRequest request, byte[] body, ClientHttpRequestExecution execution) {
        try {
            return interceptor.intercept(request, body, execution);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
