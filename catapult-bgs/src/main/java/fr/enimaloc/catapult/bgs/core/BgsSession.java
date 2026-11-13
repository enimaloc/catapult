package fr.enimaloc.catapult.bgs.core;

import bgs.protocol.RpcProto.Header;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import fr.enimaloc.catapult.bgs.proto.BgsServices;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * BGS RPC over a {@link BgsTransport}. A virtual reader thread decodes frames and posts them to a
 * single-thread executor ({@link #loop()}) that does every write, owns the pending-call table and runs
 * every callback. A callback that throws is swallowed so it cannot kill the session.
 */
public final class BgsSession implements BgsChannel {

    private static final System.Logger LOG = System.getLogger(BgsSession.class.getName());

    private static final int REPLY = 0xFE;

    /** Known server-to-client methods, to know whether an unregistered incoming call expects an ack. */
    private static final Map<Long, ServiceMethod<?, ?>> KNOWN = knownMethods();

    private record Handler(ServiceMethod<?, ?> method, Consumer<ByteString> body) {
    }

    private final BgsTransport transport;
    private final OutputStream out;
    private final ExecutorService loop = Executors.newSingleThreadExecutor(
            r -> Thread.ofPlatform().name("bgs-session").daemon().unstarted(r));
    private final Map<Integer, CompletableFuture<ByteString>> pending = new HashMap<>();
    private final Map<Long, Handler> handlers = new ConcurrentHashMap<>();
    private final List<Consumer<Throwable>> closeListeners = new ArrayList<>();
    private volatile String ciid = "";
    private int nextToken;
    private volatile boolean closed;
    private volatile Throwable closeCause;

    private BgsSession(BgsTransport transport) {
        this.transport = transport;
        this.out = transport.out();
    }

    public static BgsSession connect(String host, int port) throws IOException {
        return open(BgsTransport.tls(host, port));
    }

    public static BgsSession open(BgsTransport transport) {
        BgsSession session = new BgsSession(transport);
        Thread.ofVirtual().name("bgs-reader").start(session::readLoop);
        return session;
    }

    // --- API (any thread) ---

    @Override
    public <R extends Message> CompletableFuture<R> call(ServiceMethod<?, R> method, Message request) {
        CompletableFuture<R> result = new CompletableFuture<>();
        boolean withCiid = method.service().contains("friends") || method.service().contains("presence");
        post(() -> {
            if (!method.hasResponse()) {
                send(method.serviceHash(), method.methodId(), request.toByteString(), withCiid, null);
                result.complete(null);
                return;
            }
            CompletableFuture<ByteString> raw = new CompletableFuture<>();
            raw.whenComplete((body, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                try {
                    result.complete(method.responseParser().parseFrom(body));
                } catch (InvalidProtocolBufferException e) {
                    result.completeExceptionally(e);
                }
            });
            send(method.serviceHash(), method.methodId(), request.toByteString(), withCiid, raw);
        }, result);
        return result;
    }

    @Override
    public void callNoReply(ServiceMethod<?, ?> method, Message request) {
        boolean withCiid = method.service().contains("friends") || method.service().contains("presence");
        post(() -> send(method.serviceHash(), method.methodId(), request.toByteString(), withCiid, null), null);
    }

    @Override
    public CompletableFuture<ByteString> callRaw(int serviceHash, int methodId, ByteString body, boolean withCiid) {
        CompletableFuture<ByteString> result = new CompletableFuture<>();
        post(() -> send(serviceHash, methodId, body, withCiid, result), result);
        return result;
    }

    @Override
    public <Q extends Message> void on(ServiceMethod<Q, ?> method, Consumer<Q> handler) {
        handlers.put(key(method.serviceHash(), method.methodId()), new Handler(method, body -> {
            try {
                handler.accept(method.requestParser().parseFrom(body));
            } catch (InvalidProtocolBufferException e) {
                // an undecodable notification is dropped; the ack is already sent
                LOG.log(System.Logger.Level.WARNING, "Undecodable BGS notification " + describe(method.serviceHash(),
                        method.methodId()), e);
            }
        }));
    }

    @Override
    public void onRaw(int serviceHash, int methodId, Consumer<ByteString> handler) {
        handlers.put(key(serviceHash, methodId), new Handler(null, handler));
    }

    @Override
    public void setCiid(String ciid) {
        this.ciid = ciid == null ? "" : ciid;
    }

    @Override
    public void onClose(Consumer<Throwable> listener) {
        // Once the loop is shut down nothing can be posted: notify the late listener directly
        Runnable notifyNow = () -> runQuietly("close listener", () -> listener.accept(closeCause));
        if (closed) {
            notifyNow.run();
            return;
        }
        try {
            loop.execute(() -> {
                if (closed) {
                    notifyNow.run();
                } else {
                    closeListeners.add(listener);
                }
            });
        } catch (RejectedExecutionException e) {
            notifyNow.run();
        }
    }

    @Override
    public Executor loop() {
        return loop;
    }

    @Override
    public void close() {
        post(() -> shutdown(null), null);
    }

    // --- loop thread ---

    private void send(int serviceHash, int methodId, ByteString body, boolean withCiid,
                      CompletableFuture<ByteString> reply) {
        if (closed) {
            if (reply != null) {
                reply.completeExceptionally(new IOException("Session closed"));
            }
            return;
        }
        int token = nextToken++;
        Header.Builder header = Header.newBuilder().setServiceId(0).setServiceHash(serviceHash)
                .setMethodId(methodId).setToken(token);
        if (withCiid && !ciid.isEmpty()) {
            header.setCiid(ciid);
        }
        if (reply != null) {
            pending.put(token, reply);
        }
        write(header.build(), body);
    }

    private void write(Header header, ByteString body) {
        try {
            out.write(FrameCodec.encode(header, body));
            out.flush();
        } catch (IOException e) {
            shutdown(e);
        }
    }

    private void onFrame(FrameCodec.Frame frame) {
        Header h = frame.header();
        if (h.getServiceId() == REPLY) {
            CompletableFuture<ByteString> reply = pending.remove(h.getToken());
            if (reply == null) {
                return;
            }
            if (h.getStatus() != 0) {
                reply.completeExceptionally(new BgsRpcException(h.getStatus()));
            } else {
                reply.complete(frame.body());
            }
            return;
        }
        long key = key(h.getServiceHash(), h.getMethodId());
        Handler handler = handlers.get(key);
        ServiceMethod<?, ?> method = handler != null && handler.method() != null ? handler.method() : KNOWN.get(key);
        // Same as the reference client: unknown methods and methods with a response get an empty ack
        if (method == null || method.hasResponse()) {
            write(Header.newBuilder().setServiceId(REPLY).setToken(h.getToken()).build(), ByteString.EMPTY);
        }
        if (handler != null) {
            runQuietly("handler " + describe(h.getServiceHash(), h.getMethodId()), () -> handler.body().accept(frame.body()));
        }
    }

    private void shutdown(Throwable cause) {
        if (closed) {
            return;
        }
        closeCause = cause;
        closed = true;
        runQuietly("transport close", transport::close);
        Throwable failure = cause != null ? cause : new IOException("Session closed");
        pending.values().forEach(f -> f.completeExceptionally(failure));
        pending.clear();
        closeListeners.forEach(l -> runQuietly("close listener", () -> l.accept(cause)));
        closeListeners.clear();
        loop.shutdown();
    }

    // --- reader thread ---

    private void readLoop() {
        try {
            var in = transport.in();
            while (true) {
                FrameCodec.Frame frame = FrameCodec.read(in);
                post(() -> onFrame(frame), null);
            }
        } catch (Throwable t) {
            post(() -> shutdown(t), null);
        }
    }

    // --- helpers ---

    private void post(Runnable task, CompletableFuture<?> onRejected) {
        try {
            loop.execute(() -> runQuietly("session task", task::run));
        } catch (RejectedExecutionException e) {
            if (onRejected != null) {
                onRejected.completeExceptionally(new IOException("Session closed"));
            }
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    /** A failing callback must not take the session down: it is logged (never with the body) and dropped. */
    private static void runQuietly(String what, Action action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "BGS session " + what + " failed", e);
        }
    }

    private static String describe(int serviceHash, int methodId) {
        return "0x" + Integer.toHexString(serviceHash).toUpperCase() + "." + methodId;
    }

    private static long key(int serviceHash, int methodId) {
        return ((long) serviceHash << 32) | (methodId & 0xFFFFFFFFL);
    }

    private static Map<Long, ServiceMethod<?, ?>> knownMethods() {
        Map<Long, ServiceMethod<?, ?>> map = new HashMap<>();
        for (Field f : BgsServices.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && ServiceMethod.class.isAssignableFrom(f.getType())) {
                try {
                    ServiceMethod<?, ?> m = (ServiceMethod<?, ?>) f.get(null);
                    map.put(key(m.serviceHash(), m.methodId()), m);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return map;
    }
}
