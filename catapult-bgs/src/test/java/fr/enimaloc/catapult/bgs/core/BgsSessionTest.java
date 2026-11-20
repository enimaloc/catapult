package fr.enimaloc.catapult.bgs.core;

import bgs.protocol.RpcProto;
import bgs.protocol.RpcProto.Header;
import bgs.protocol.connection.v1.ConnectionServiceProto;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass;
import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.proto.BgsServices;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BgsSessionTest {

    private static final int TIMEOUT_SECONDS = 5;

    /** The server side of an in-memory transport. */
    private PipedOutputStream serverOut;
    private InputStream serverIn;
    private BgsSession session;

    @BeforeEach
    void setUp() throws IOException {
        serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 16);
        PipedOutputStream clientOut = new PipedOutputStream();
        serverIn = new PipedInputStream(clientOut, 1 << 16);
        session = BgsSession.open(new BgsTransport() {
            @Override
            public InputStream in() {
                return clientIn;
            }

            @Override
            public OutputStream out() {
                return clientOut;
            }

            @Override
            public void close() throws IOException {
                clientIn.close();
                clientOut.close();
            }
        });
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    private FrameCodec.Frame readFromClient() throws IOException {
        return FrameCodec.read(serverIn);
    }

    private void sendToClient(Header header, ByteString body) throws IOException {
        serverOut.write(FrameCodec.encode(header, body));
        serverOut.flush();
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void replyCompletesThePendingCallWithTheSameToken() throws Exception {
        var future = session.call(BgsServices.CONNECTION_CONNECT, ConnectionServiceProto.ConnectRequest.getDefaultInstance());
        FrameCodec.Frame sent = readFromClient();
        var response = ConnectionServiceProto.ConnectResponse.newBuilder().setServerId(
                RpcProto.ProcessId.newBuilder().setLabel(1).setEpoch(2)).build();

        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(sent.header().getToken()).build(),
                response.toByteString());

        assertThat(await(future)).isEqualTo(response);
        assertThat(sent.header().getServiceHash()).isEqualTo(BgsServices.CONNECTION_CONNECT.serviceHash());
        assertThat(sent.header().getMethodId()).isEqualTo(BgsServices.CONNECTION_CONNECT.methodId());
    }

    @Test
    void nonZeroStatusFailsTheFutureWithBgsRpcException() throws Exception {
        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        FrameCodec.Frame sent = readFromClient();

        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(sent.header().getToken()).setStatus(7).build(),
                ByteString.EMPTY);

        assertThatThrownBy(() -> await(future)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(BgsRpcException.class, e -> assertThat(e.status()).isEqualTo(7));
    }

    @Test
    void tokensIncrementPerCall() throws Exception {
        session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        session.callNoReply(BgsServices.CONNECTION_KEEP_ALIVE, RpcProto.NoData.getDefaultInstance());

        int first = readFromClient().header().getToken();
        int second = readFromClient().header().getToken();

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void ciidOnlyOnCallsThatAskForIt() throws Exception {
        session.setCiid("abc");
        session.callRaw(0x1234, 1, ByteString.EMPTY, true);
        session.callRaw(0x1234, 2, ByteString.EMPTY, false);
        session.call(BgsServices.CONNECTION_CONNECT, ConnectionServiceProto.ConnectRequest.getDefaultInstance());
        session.call(BgsServices.FRIENDS_GET_FRIENDS, FriendsServiceOuterClass.GetFriendsRequest.getDefaultInstance());

        assertThat(readFromClient().header().getCiid()).isEqualTo("abc");
        assertThat(readFromClient().header().hasCiid()).isFalse();
        assertThat(readFromClient().header().hasCiid()).isFalse();
        assertThat(readFromClient().header().getCiid()).isEqualTo("abc");
    }

    @Test
    void incomingCallIsAcknowledgedThenDispatched() throws Exception {
        // A server call that expects a response: ack first, then the handler on the loop thread
        var method = new ServiceMethod<>("test.Service", 0xAAAA, 3,
                FriendsListenerOuterClass.FriendAddedNotification.parser(), RpcProto.NoData.parser());
        var received = new CompletableFuture<FriendsListenerOuterClass.FriendAddedNotification>();
        var thread = new AtomicReference<Thread>();
        var loopThread = new CompletableFuture<Thread>();
        session.loop().execute(() -> loopThread.complete(Thread.currentThread()));
        session.on(method, n -> {
            thread.set(Thread.currentThread());
            received.complete(n);
        });
        var notification = FriendsListenerOuterClass.FriendAddedNotification.getDefaultInstance();

        sendToClient(Header.newBuilder().setServiceId(0).setServiceHash(0xAAAA).setMethodId(3).setToken(42).build(),
                notification.toByteString());

        FrameCodec.Frame ack = readFromClient();
        assertThat(ack.header().getServiceId()).isEqualTo(0xFE);
        assertThat(ack.header().getToken()).isEqualTo(42);
        assertThat(ack.header().getSize()).isZero();
        assertThat(await(received)).isEqualTo(notification);
        assertThat(thread.get()).isSameAs(await(loopThread));
    }

    @Test
    void noResponseNotificationIsDispatchedWithoutAck() throws Exception {
        var received = new CompletableFuture<FriendsListenerOuterClass.FriendAddedNotification>();
        session.on(BgsServices.FRIENDS_LISTENER_ON_FRIEND_ADDED, received::complete);
        var notification = FriendsListenerOuterClass.FriendAddedNotification.getDefaultInstance();
        var method = BgsServices.FRIENDS_LISTENER_ON_FRIEND_ADDED;

        sendToClient(Header.newBuilder().setServiceId(0).setServiceHash(method.serviceHash())
                .setMethodId(method.methodId()).setToken(1).build(), notification.toByteString());
        assertThat(await(received)).isEqualTo(notification);
        // the next frame written by the client is this call, not an ack
        session.callRaw(0x1234, 1, ByteString.EMPTY, false);

        assertThat(readFromClient().header().getServiceHash()).isEqualTo(0x1234);
    }

    @Test
    void unknownIncomingServiceIsAcknowledged() throws Exception {
        sendToClient(Header.newBuilder().setServiceId(0).setServiceHash(0xDEAD).setMethodId(9).setToken(7).build(),
                ByteString.copyFromUtf8("x"));

        FrameCodec.Frame ack = readFromClient();
        assertThat(ack.header().getServiceId()).isEqualTo(0xFE);
        assertThat(ack.header().getToken()).isEqualTo(7);
        // the session is still alive afterwards
        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(readFromClient().header().getToken()).build(),
                ByteString.copyFromUtf8("ok"));
        assertThat(await(future).toStringUtf8()).isEqualTo("ok");
    }

    @Test
    void aThrowingHandlerDoesNotKillTheSession() throws Exception {
        session.onRaw(0xBEEF, 1, body -> {
            throw new IllegalStateException("boom");
        });
        sendToClient(Header.newBuilder().setServiceId(0).setServiceHash(0xBEEF).setMethodId(1).setToken(1).build(),
                ByteString.EMPTY);
        readFromClient(); // ack

        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(readFromClient().header().getToken()).build(),
                ByteString.copyFromUtf8("ok"));

        assertThat(await(future).toStringUtf8()).isEqualTo("ok");
    }

    @Test
    void eofClosesTheSessionAndFailsPendingCalls() throws Exception {
        var closed = new CompletableFuture<Throwable>();
        session.onClose(cause -> closed.complete(cause));
        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        readFromClient();

        serverOut.close();

        assertThat(await(closed)).isNotNull();
        assertThatThrownBy(() -> await(future)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> await(session.callRaw(0x1234, 1, ByteString.EMPTY, false)))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void localCloseFailsPendingCallsAndNotifiesWithNoCause() throws Exception {
        var closed = new CompletableFuture<Throwable>();
        session.onClose(closed::complete);
        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);

        session.close();

        assertThat(await(closed)).isNull();
        assertThatThrownBy(() -> await(future)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void closeListenerRegisteredAfterCloseIsStillNotified() throws Exception {
        session.close();
        var first = new CompletableFuture<Throwable>();
        session.onClose(first::complete);
        // the loop is shut down by then: a second late registration must also be notified
        var second = new CompletableFuture<Throwable>();
        session.onClose(second::complete);

        assertThat(await(first)).isNull();
        assertThat(await(second)).isNull();
    }

    @Test
    void closeListenerRegisteredAfterEofGetsTheCause() throws Exception {
        var closed = new CompletableFuture<Throwable>();
        session.onClose(closed::complete);
        serverOut.close();
        await(closed);

        var late = new CompletableFuture<Throwable>();
        session.onClose(late::complete);

        assertThat(await(late)).isNotNull();
    }

    // --- wire tap ---

    /** A tap that keeps every exchange it is handed, while {@link #wanted} is true. */
    private static final class RecordingTap implements WireTap {

        final AtomicBoolean wanted = new AtomicBoolean(true);
        final LinkedBlockingQueue<WireExchange> exchanges = new LinkedBlockingQueue<>();

        @Override
        public boolean wants() {
            return wanted.get();
        }

        @Override
        public void onExchange(WireExchange exchange) {
            exchanges.add(exchange);
        }

        WireExchange next() throws InterruptedException {
            WireExchange exchange = exchanges.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(exchange).as("an exchange was handed to the tap").isNotNull();
            return exchange;
        }
    }

    @Test
    void tapGetsACallAndItsReply() throws Exception {
        var tap = new RecordingTap();
        session.setWireTap(tap);
        var request = ConnectionServiceProto.ConnectRequest.newBuilder().setUseBindlessRpc(true).build();
        var future = session.call(BgsServices.CONNECTION_CONNECT, request);
        FrameCodec.Frame sent = readFromClient();
        var response = ConnectionServiceProto.ConnectResponse.newBuilder().setServerId(
                RpcProto.ProcessId.newBuilder().setLabel(1).setEpoch(2)).build();

        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(sent.header().getToken()).build(),
                response.toByteString());
        await(future);

        WireExchange exchange = tap.next();
        assertThat(exchange.initiatedLocally()).isTrue();
        assertThat(exchange.method()).isSameAs(BgsServices.CONNECTION_CONNECT);
        assertThat(exchange.requestHeader()).isEqualTo(sent.header());
        assertThat(exchange.requestBody()).isEqualTo(request.toByteString());
        assertThat(exchange.replyHeader().getServiceId()).isEqualTo(0xFE);
        assertThat(exchange.replyHeader().getToken()).isEqualTo(sent.header().getToken());
        assertThat(exchange.replyHeader().getStatus()).isZero();
        assertThat(exchange.replyBody()).isEqualTo(response.toByteString());
        assertThat(exchange.error()).isNull();
        assertThat(exchange.at()).isNotNull();
        assertThat(exchange.durationNanos()).isPositive();
    }

    @Test
    void tapGetsAFailedCallWithItsError() throws Exception {
        var tap = new RecordingTap();
        session.setWireTap(tap);
        var future = session.callRaw(0x1234, 1, ByteString.copyFromUtf8("q"), false);
        FrameCodec.Frame sent = readFromClient();

        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(sent.header().getToken()).setStatus(7).build(),
                ByteString.EMPTY);
        assertThatThrownBy(() -> await(future)).isInstanceOf(ExecutionException.class);

        WireExchange exchange = tap.next();
        assertThat(exchange.method()).isNull();
        assertThat(exchange.requestBody().toStringUtf8()).isEqualTo("q");
        assertThat(exchange.replyHeader().getStatus()).isEqualTo(7);
        assertThat(exchange.error()).contains("7");
    }

    @Test
    void tapGetsAnOutgoingCallWithNoReplyAtSend() throws Exception {
        var tap = new RecordingTap();
        session.setWireTap(tap);
        session.callNoReply(BgsServices.CONNECTION_KEEP_ALIVE, RpcProto.NoData.getDefaultInstance());
        FrameCodec.Frame sent = readFromClient();

        WireExchange exchange = tap.next();
        assertThat(exchange.initiatedLocally()).isTrue();
        assertThat(exchange.method()).isSameAs(BgsServices.CONNECTION_KEEP_ALIVE);
        assertThat(exchange.requestHeader()).isEqualTo(sent.header());
        assertThat(exchange.replyHeader()).isNull();
        assertThat(exchange.replyBody()).isNull();
        assertThat(exchange.error()).isNull();
    }

    @Test
    void tapGetsAnIncomingNotificationWithItsAck() throws Exception {
        var tap = new RecordingTap();
        session.setWireTap(tap);
        var method = new ServiceMethod<>("test.Service", 0xAAAA, 3,
                FriendsListenerOuterClass.FriendAddedNotification.parser(), RpcProto.NoData.parser());
        session.on(method, n -> {
        });
        Header incoming = Header.newBuilder().setServiceId(0).setServiceHash(0xAAAA).setMethodId(3).setToken(42)
                .build();

        sendToClient(incoming, ByteString.copyFromUtf8("n"));
        FrameCodec.Frame ack = readFromClient();

        WireExchange exchange = tap.next();
        assertThat(exchange.initiatedLocally()).isFalse();
        assertThat(exchange.method()).isSameAs(method);
        assertThat(exchange.requestHeader().getToken()).isEqualTo(42);
        assertThat(exchange.requestHeader().getServiceHash()).isEqualTo(0xAAAA);
        assertThat(exchange.requestBody().toStringUtf8()).isEqualTo("n");
        assertThat(exchange.replyHeader()).isEqualTo(ack.header());
        assertThat(exchange.replyBody()).isEqualTo(ByteString.EMPTY);
        assertThat(exchange.error()).isNull();
    }

    @Test
    void tapGetsAnIncomingNotificationWithNoAck() throws Exception {
        var tap = new RecordingTap();
        session.setWireTap(tap);
        var method = BgsServices.FRIENDS_LISTENER_ON_FRIEND_ADDED;

        sendToClient(Header.newBuilder().setServiceId(0).setServiceHash(method.serviceHash())
                .setMethodId(method.methodId()).setToken(1).build(), ByteString.EMPTY);

        WireExchange exchange = tap.next();
        assertThat(exchange.initiatedLocally()).isFalse();
        assertThat(exchange.method()).isSameAs(method);
        assertThat(exchange.replyHeader()).isNull();
        assertThat(exchange.replyBody()).isNull();
        assertThat(exchange.durationNanos()).isZero();
    }

    @Test
    void tapGetsACallStillPendingWhenTheSessionCloses() throws Exception {
        var tap = new RecordingTap();
        session.setWireTap(tap);
        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        FrameCodec.Frame sent = readFromClient();

        session.close();
        assertThatThrownBy(() -> await(future)).isInstanceOf(ExecutionException.class);

        WireExchange exchange = tap.next();
        assertThat(exchange.requestHeader()).isEqualTo(sent.header());
        assertThat(exchange.replyHeader()).isNull();
        assertThat(exchange.replyBody()).isNull();
        assertThat(exchange.error()).isNotBlank();
    }

    @Test
    void tapNotWantedKeepsNothing() throws Exception {
        var tap = new RecordingTap();
        tap.wanted.set(false);
        session.setWireTap(tap);
        session.callNoReply(BgsServices.CONNECTION_KEEP_ALIVE, RpcProto.NoData.getDefaultInstance());
        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        readFromClient();
        FrameCodec.Frame sent = readFromClient();
        // wanted again before the reply: the call was not kept at send time, so it stays untold
        tap.wanted.set(true);
        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(sent.header().getToken()).build(),
                ByteString.EMPTY);
        await(future);
        tap.wanted.set(false);
        sendToClient(Header.newBuilder().setServiceId(0).setServiceHash(0xDEAD).setMethodId(9).setToken(7).build(),
                ByteString.EMPTY);
        readFromClient(); // ack
        var drained = new CompletableFuture<Void>();
        session.loop().execute(() -> drained.complete(null));
        await(drained);

        assertThat(tap.exchanges).isEmpty();
    }

    @Test
    void aThrowingTapDoesNotBreakTheSession() throws Exception {
        var calls = new AtomicInteger();
        session.setWireTap(new WireTap() {
            @Override
            public boolean wants() {
                return true;
            }

            @Override
            public void onExchange(WireExchange exchange) {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            }
        });
        sendToClient(Header.newBuilder().setServiceId(0).setServiceHash(0xDEAD).setMethodId(9).setToken(7).build(),
                ByteString.EMPTY);
        readFromClient(); // ack

        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(readFromClient().header().getToken()).build(),
                ByteString.copyFromUtf8("ok"));

        assertThat(await(future).toStringUtf8()).isEqualTo("ok");
        var drained = new CompletableFuture<Void>();
        session.loop().execute(() -> drained.complete(null));
        await(drained);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aThrowingWantsDoesNotBreakTheSession() throws Exception {
        session.setWireTap(new WireTap() {
            @Override
            public boolean wants() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onExchange(WireExchange exchange) {
            }
        });

        var future = session.callRaw(0x1234, 1, ByteString.EMPTY, false);
        sendToClient(Header.newBuilder().setServiceId(0xFE).setToken(readFromClient().header().getToken()).build(),
                ByteString.copyFromUtf8("ok"));

        assertThat(await(future).toStringUtf8()).isEqualTo("ok");
    }
}
