package fr.enimaloc.catapult.service.ankama.chat;

import com.google.protobuf.CodedOutputStream;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Outer;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Payload;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Request;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.net.SocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnkamaChatClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ServerSocket server;
    private java.util.concurrent.ScheduledThreadPoolExecutor scheduler;
    private AnkamaChatClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        client = newClient(Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        scheduler.shutdownNow();
        server.close();
    }

    private AnkamaChatClient newClient(Duration heartbeat) {
        return new AnkamaChatClient(SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort(), heartbeat,
                scheduler);
    }

    /** Server side of one connection, driven by the test. */
    private static final class Peer {
        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        Outer read() throws IOException {
            return ChatFrames.read(in);
        }

        void send(int kind, byte[] body) throws IOException {
            ChatFrames.write(out, kind, body);
        }
    }

    private record Connected(Peer peer, JsonNode handshake, List<String> kinds) {
    }

    private static final class RecordingListener implements ChatListener {
        final BlockingQueue<ChatMessages.Event> events = new LinkedBlockingQueue<>();
        final BlockingQueue<java.util.Optional<Throwable>> closed = new LinkedBlockingQueue<>();

        @Override
        public void onEvent(ChatMessages.Event event) {
            events.add(event);
        }

        @Override
        public void onClosed(Throwable cause) {
            closed.add(java.util.Optional.ofNullable(cause));
        }
    }

    /** Runs {@code connect} on a thread while the test plays the server, answering the handshake with {@code answer}. */
    private Connected handshake(RecordingListener listener, String answer) throws Exception {
        CompletableFuture<Void> connecting = CompletableFuture.runAsync(() -> {
            try {
                client.connect("secret-token", "device-1", listener);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Peer peer = new Peer(server.accept());
        peer.socket.setSoTimeout(5000);
        Outer ping = peer.read();
        assertThat(ping.kind()).isEqualTo(ChatFrames.KIND_PING);
        peer.send(ChatFrames.KIND_PONG, ping.body());
        Outer json = peer.read();
        assertThat(json.kind()).isEqualTo(ChatFrames.KIND_DATA);
        peer.send(ChatFrames.KIND_DATA, answer.getBytes(StandardCharsets.UTF_8));
        try {
            connecting.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof java.io.UncheckedIOException u) {
                throw u.getCause();
            }
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
        return new Connected(peer, MAPPER.readTree(new String(json.body(), StandardCharsets.UTF_8)), List.of());
    }

    @Test
    void handshake_pingThenTokenJson_thenSuccess() throws Exception {
        Connected c = handshake(new RecordingListener(), "{\"success\": true}");

        assertThat(c.handshake().get("token").asString()).isEqualTo("secret-token");
        assertThat(c.handshake().get("clientType").asInt()).isEqualTo(1);
        assertThat(c.handshake().get("osType").asInt()).isEqualTo(2);
        assertThat(c.handshake().get("deviceType").asInt()).isEqualTo(1);
        assertThat(c.handshake().get("deviceId").asString()).isEqualTo("device-1");
    }

    @Test
    void refusedAuthentication_isAnAnkamaChatAuthException() {
        assertThatThrownBy(() -> handshake(new RecordingListener(), "{\"success\": false}"))
                .isInstanceOf(AnkamaChatAuthException.class)
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void request_isAnsweredByItsCorrelationId() throws Exception {
        Connected c = handshake(new RecordingListener(), "{\"success\": true}");
        Payload p1 = new Payload(11, new byte[]{1});
        Payload p2 = new Payload(22, new byte[]{2});

        CompletableFuture<Response> first = client.request(p1);
        CompletableFuture<Response> second = client.request(p2);

        Request r1 = (Request) ChatFrames.decode(c.peer().read().body());
        Request r2 = (Request) ChatFrames.decode(c.peer().read().body());
        assertThat(r1.correlationId()).isEqualTo(1);
        assertThat(r2.correlationId()).isEqualTo(2);
        assertThat(r1.payload().id()).isEqualTo(11);

        c.peer().send(ChatFrames.KIND_DATA,
                ChatFrames.encode(new Response(2, 0, new Payload(222, new byte[]{9}))));
        c.peer().send(ChatFrames.KIND_DATA, ChatFrames.encode(new Response(1, 7, null)));

        assertThat(second.get(5, TimeUnit.SECONDS).payload().id()).isEqualTo(222);
        Response answer = first.get(5, TimeUnit.SECONDS);
        assertThat(answer.status()).isEqualTo(7);
        assertThat(answer.payload()).isNull();
    }

    @Test
    void events_reachTheListener() throws Exception {
        RecordingListener listener = new RecordingListener();
        Connected c = handshake(listener, "{\"success\": true}");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        CodedOutputStream o = CodedOutputStream.newInstance(data);
        o.writeInt64(1, 42L);
        o.flush();

        c.peer().send(ChatFrames.KIND_DATA, ChatFrames.encode(new ChatFrames.Event(
                new Payload(ChatMessages.USER_ENDPOINT_PROPERTIES_UPDATED_EVT, data.toByteArray()))));

        ChatMessages.Event event = listener.events.poll(5, TimeUnit.SECONDS);
        assertThat(event).isEqualTo(new ChatMessages.EndpointsUpdated(42L, List.of()));
    }

    @Test
    void heartbeats_areSent() throws Exception {
        client = newClient(Duration.ofMillis(50));
        Connected c = handshake(new RecordingListener(), "{\"success\": true}");

        assertThat(c.peer().read().kind()).isEqualTo(ChatFrames.KIND_HEARTBEAT);
        assertThat(c.peer().read().kind()).isEqualTo(ChatFrames.KIND_HEARTBEAT);
    }

    @Test
    void serverClosing_callsOnClosed_andFailsPendingRequests() throws Exception {
        RecordingListener listener = new RecordingListener();
        Connected c = handshake(listener, "{\"success\": true}");
        CompletableFuture<Response> pending = client.request(new Payload(1, new byte[0]));
        c.peer().read();

        c.peer().socket.close();

        java.util.Optional<Throwable> cause = listener.closed.poll(5, TimeUnit.SECONDS);
        assertThat(cause).isNotNull();
        assertThat(cause).isPresent();
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void close_callsOnClosedWithoutCause() throws Exception {
        RecordingListener listener = new RecordingListener();
        handshake(listener, "{\"success\": true}");

        client.close();

        assertThat(listener.closed.poll(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void connectionDyingRightAfterTheHandshake_leavesNoHeartbeatScheduled() throws Exception {
        RecordingListener listener = new RecordingListener();
        Connected c = handshake(listener, "{\"success\": true}");
        c.peer().socket.close();

        assertThat(listener.closed.poll(5, TimeUnit.SECONDS)).isNotNull();
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(scheduler.getQueue()).isEmpty());
    }

    @Test
    void tlsSockets_verifyTheHostName() throws Exception {
        javax.net.ssl.SSLSocket ssl = org.mockito.Mockito.mock(javax.net.ssl.SSLSocket.class);
        org.mockito.Mockito.when(ssl.getSSLParameters()).thenReturn(new javax.net.ssl.SSLParameters());
        org.mockito.Mockito.when(ssl.getInputStream()).thenReturn(InputStream.nullInputStream());
        org.mockito.Mockito.when(ssl.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        SocketFactory factory = new SocketFactory() {
            @Override public Socket createSocket(String h, int p) { return ssl; }
            @Override public Socket createSocket(String h, int p, java.net.InetAddress a, int lp) { return ssl; }
            @Override public Socket createSocket(java.net.InetAddress a, int p) { return ssl; }
            @Override public Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress la, int lp) {
                return ssl;
            }
        };
        AnkamaChatClient tls = new AnkamaChatClient(factory, "chat.example", 6337, Duration.ofSeconds(30), scheduler);

        assertThatThrownBy(() -> tls.connect("t", "d", new RecordingListener())).isInstanceOf(IOException.class);

        org.mockito.ArgumentCaptor<javax.net.ssl.SSLParameters> applied =
                org.mockito.ArgumentCaptor.forClass(javax.net.ssl.SSLParameters.class);
        org.mockito.Mockito.verify(ssl).setSSLParameters(applied.capture());
        assertThat(applied.getValue().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
    }
}
