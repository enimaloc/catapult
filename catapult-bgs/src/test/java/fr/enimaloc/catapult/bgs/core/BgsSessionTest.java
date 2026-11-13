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
import java.util.concurrent.TimeUnit;
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
}
