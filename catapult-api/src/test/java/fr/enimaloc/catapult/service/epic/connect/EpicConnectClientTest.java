package fr.enimaloc.catapult.service.epic.connect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class EpicConnectClientTest {

    private static final String BOT = "27871f9dd56446c8a1f1d3db1aaa8a7e";
    private static final String TOKEN = "eg1~secret/token+value";
    private static final URI BASE = URI.create("wss://connect.example.test");

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /** A WebSocket that records what is sent; the test drives the captured listener. */
    private static final class FakeSocket implements WebSocket {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean aborted;
        volatile java.util.function.Consumer<String> onSend = frame -> { };
        volatile CompletableFuture<WebSocket> heartbeatResult;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sent.add(data.toString());
            onSend.accept(data.toString());
            if (StompFrames.HEARTBEAT.contentEquals(data) && heartbeatResult != null) {
                return heartbeatResult;
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }

    private static final class Recorder implements EpicConnectListener {
        final List<String> connected = new CopyOnWriteArrayList<>();
        final List<EpicConnectMessage> messages = new CopyOnWriteArrayList<>();
        final List<Throwable> closes = new CopyOnWriteArrayList<>();

        @Override
        public void onConnected(String connectionId) {
            connected.add(connectionId);
        }

        @Override
        public void onMessage(EpicConnectMessage message) {
            messages.add(message);
        }

        @Override
        public void onClosed(Throwable cause) {
            closes.add(cause);
        }
    }

    private final FakeSocket socket = new FakeSocket();
    private final List<URI> opened = new ArrayList<>();
    private WebSocket.Listener wire;

    private EpicConnectClient client(Duration heartbeat) {
        return new EpicConnectClient((uri, listener) -> {
            opened.add(uri);
            wire = listener;
            return CompletableFuture.completedFuture(socket);
        }, BASE, heartbeat, scheduler);
    }

    private static String message(String body) {
        return "MESSAGE\ndestination:_/account/" + BOT + "\nsubscription:sub-0\nmessage-id:1\n"
                + "content-type:application/json\n\n" + body + "\0";
    }

    @Test
    void connect_sendsConnectThenSubscribe() {
        client(Duration.ofSeconds(30)).connect(TOKEN, BOT, new Recorder());

        assertThat(socket.sent).containsExactly(StompFrames.connect(), StompFrames.subscribe(BOT));
        assertThat(opened).hasSize(1);
        assertThat(opened.get(0).toString())
                .startsWith("wss://connect.example.test/?auth-token=eg1%7Esecret%2Ftoken%2Bvalue")
                .endsWith("&accept-language=en-US");
    }

    @Test
    void connectedMessage_givesTheConnectionId() {
        Recorder recorder = new Recorder();
        client(Duration.ofSeconds(30)).connect(TOKEN, BOT, recorder);

        wire.onText(socket, message("{\"type\":\"core.connect.v1.connected\",\"connectionId\":\"c-1\"}"), true);
        wire.onText(socket, message("{\"type\":\"presence.v1.UPDATE\",\"connectionId\":\"c-1\","
                + "\"payload\":{\"a\":1}}"), true);

        assertThat(recorder.connected).containsExactly("c-1");
        assertThat(recorder.messages).hasSize(1);
        assertThat(recorder.messages.get(0).type()).isEqualTo("presence.v1.UPDATE");
    }

    @Test
    void binaryFrames_areDecodedLikeText() {
        Recorder recorder = new Recorder();
        client(Duration.ofSeconds(30)).connect(TOKEN, BOT, recorder);

        byte[] bytes = message("{\"type\":\"core.connect.v1.connected\",\"connectionId\":\"c-2\"}")
                .getBytes(StandardCharsets.UTF_8);
        wire.onBinary(socket, ByteBuffer.wrap(bytes), true);

        assertThat(recorder.connected).containsExactly("c-2");
    }

    @Test
    void partialMessages_areReassembled() {
        Recorder recorder = new Recorder();
        client(Duration.ofSeconds(30)).connect(TOKEN, BOT, recorder);

        String text = message("{\"type\":\"presence.v1.UPDATE\",\"connectionId\":\"c-3\"}");
        wire.onText(socket, text.substring(0, 40), false);
        wire.onText(socket, text.substring(40), true);
        byte[] bytes = message("{\"type\":\"core.connect.v1.connected\",\"connectionId\":\"c-4\"}")
                .getBytes(StandardCharsets.UTF_8);
        wire.onBinary(socket, ByteBuffer.wrap(bytes, 0, 30), false);
        wire.onBinary(socket, ByteBuffer.wrap(bytes, 30, bytes.length - 30), true);

        assertThat(recorder.messages).hasSize(1);
        assertThat(recorder.messages.get(0).connectionId()).isEqualTo("c-3");
        assertThat(recorder.connected).containsExactly("c-4");
    }

    @Test
    void heartbeats_areSent() {
        client(Duration.ofMillis(50)).connect(TOKEN, BOT, new Recorder());

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(
                socket.sent.stream().filter(StompFrames.HEARTBEAT::equals).count()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void stompError_closesWithItsMessage() {
        Recorder recorder = new Recorder();
        client(Duration.ofSeconds(30)).connect(TOKEN, BOT, recorder);

        wire.onText(socket, "ERROR\nmessage:Bad token\n\n\0", true);

        assertThat(recorder.closes).hasSize(1);
        assertThat(recorder.closes.get(0)).isInstanceOf(EpicConnectException.class).hasMessageContaining("Bad token");
        assertThat(socket.aborted).isTrue();
    }

    @Test
    void close_reportsANullCause_once() {
        Recorder recorder = new Recorder();
        EpicConnectClient client = client(Duration.ofSeconds(30));
        client.connect(TOKEN, BOT, recorder);

        client.close();
        client.close();

        assertThat(recorder.closes).containsExactly((Throwable) null);
    }

    @Test
    void serverClose_reportsAnException() {
        Recorder recorder = new Recorder();
        client(Duration.ofSeconds(30)).connect(TOKEN, BOT, recorder);

        wire.onClose(socket, 1011, "boom");

        assertThat(recorder.closes).hasSize(1);
        assertThat(recorder.closes.get(0)).isInstanceOf(EpicConnectException.class);
    }

    @Test
    void errorDuringTheHandshake_closesOnce_withoutThrowing_andStopsHeartbeats() throws Exception {
        Recorder recorder = new Recorder();
        EpicConnectClient client = client(Duration.ofMillis(20));
        socket.onSend = frame -> {
            if (frame.startsWith("SUBSCRIBE")) {
                wire.onText(socket, "ERROR\nmessage:nope\n\n\0", true);
            }
        };

        client.connect(TOKEN, BOT, recorder);
        Thread.sleep(200);

        assertThat(recorder.closes).hasSize(1);
        assertThat(recorder.closes.get(0)).hasMessageContaining("nope");
        assertThat(socket.sent.stream().filter(StompFrames.HEARTBEAT::equals)).isEmpty();
    }

    @Test
    void closeDuringTheOpen_abortsTheLateSocket_andClosesOnce() {
        Recorder recorder = new Recorder();
        EpicConnectClient[] holder = new EpicConnectClient[1];
        holder[0] = new EpicConnectClient((uri, listener) -> {
            wire = listener;
            holder[0].close();
            return CompletableFuture.completedFuture(socket);
        }, BASE, Duration.ofSeconds(30), scheduler);

        holder[0].connect(TOKEN, BOT, recorder);

        assertThat(socket.aborted).isTrue();
        assertThat(recorder.closes).containsExactly((Throwable) null);
        assertThat(socket.sent).isEmpty();
    }

    @Test
    void nothingIsDelivered_afterTheClose() {
        Recorder recorder = new Recorder();
        EpicConnectClient client = client(Duration.ofSeconds(30));
        client.connect(TOKEN, BOT, recorder);
        client.close();

        wire.onText(socket, message("{\"type\":\"core.connect.v1.connected\",\"connectionId\":\"c\"}"), true);
        wire.onText(socket, message("{\"type\":\"presence.v1.UPDATE\",\"connectionId\":\"c\"}"), true);

        assertThat(recorder.connected).isEmpty();
        assertThat(recorder.messages).isEmpty();
    }

    @Test
    void aFailedHeartbeatSend_closesTheConnection() {
        Recorder recorder = new Recorder();
        socket.heartbeatResult = CompletableFuture.failedFuture(new IOException("wss://x?auth-token=secret"));
        client(Duration.ofMillis(20)).connect(TOKEN, BOT, recorder);

        await().atMost(Duration.ofSeconds(3)).until(() -> !recorder.closes.isEmpty());

        assertThat(recorder.closes).hasSize(1);
        assertThat(recorder.closes.get(0)).isInstanceOf(EpicConnectException.class);
        assertNoSecret(recorder.closes.get(0));
        assertThat(socket.aborted).isTrue();
    }

    @Test
    void heartbeats_areSkippedWhileOneIsStillBeingSent() throws Exception {
        socket.heartbeatResult = new CompletableFuture<>();
        client(Duration.ofMillis(20)).connect(TOKEN, BOT, new Recorder());

        Thread.sleep(300);

        assertThat(socket.sent.stream().filter(StompFrames.HEARTBEAT::equals).count()).isEqualTo(1);
    }

    @Test
    void aClientIsSingleUse() {
        EpicConnectClient client = client(Duration.ofSeconds(30));
        client.connect(TOKEN, BOT, new Recorder());

        assertThatThrownBy(() -> client.connect(TOKEN, BOT, new Recorder()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failures_neverCarryTheToken() {
        EpicConnectClient failing = new EpicConnectClient((uri, listener) -> CompletableFuture.failedFuture(
                new IOException("connect to " + uri + " failed", new IOException("at " + uri))),
                BASE, Duration.ofSeconds(30), scheduler);

        assertThatThrownBy(() -> failing.connect(TOKEN, BOT, new Recorder()))
                .isInstanceOf(EpicConnectException.class)
                .satisfies(e -> assertNoSecret(e));

        Recorder recorder = new Recorder();
        client(Duration.ofSeconds(30)).connect(TOKEN, BOT, recorder);
        wire.onError(socket, new IOException("broken " + opened.get(0)));
        assertThat(recorder.closes).hasSize(1);
        assertNoSecret(recorder.closes.get(0));
    }

    private static void assertNoSecret(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            assertThat(String.valueOf(c)).doesNotContain("auth-token").doesNotContain("secret").doesNotContain("wss://");
        }
    }
}
