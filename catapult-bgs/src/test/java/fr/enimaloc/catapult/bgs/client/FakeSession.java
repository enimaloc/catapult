package fr.enimaloc.catapult.bgs.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import fr.enimaloc.catapult.bgs.core.BgsChannel;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * An in-memory {@link BgsChannel}: records every call with its header-level data, lets the test answer them, fail
 * them and push server notifications. The loop runs tasks on the calling thread, so tests are deterministic.
 */
final class FakeSession implements BgsChannel {

    /**
     * One outgoing call. {@code request} is null for a raw call; {@code reply} is null for a NO_RESPONSE call.
     */
    record Sent(int serviceHash, int methodId, boolean ciid, Message request, ByteString body,
                CompletableFuture<?> reply) {
    }

    /** Every call and mint, in order: "call 0x...:id", "mint". */
    final List<String> timeline = new ArrayList<>();
    final List<Sent> sent = new ArrayList<>();
    private final Map<Long, Consumer<ByteString>> handlers = new HashMap<>();
    private final List<Consumer<Throwable>> closeListeners = new ArrayList<>();
    String ciid = "";
    boolean closed;

    // --- BgsChannel ---

    @Override
    @SuppressWarnings("unchecked")
    public <R extends Message> CompletableFuture<R> call(ServiceMethod<?, R> method, Message request) {
        CompletableFuture<R> reply = new CompletableFuture<>();
        record(method.serviceHash(), method.methodId(), needsCiid(method), request, request.toByteString(),
                method.hasResponse() ? reply : null);
        if (!method.hasResponse()) {
            reply.complete(null);
        }
        return reply;
    }

    @Override
    public void callNoReply(ServiceMethod<?, ?> method, Message request) {
        record(method.serviceHash(), method.methodId(), needsCiid(method), request, request.toByteString(), null);
    }

    @Override
    public CompletableFuture<ByteString> callRaw(int serviceHash, int methodId, ByteString body, boolean withCiid) {
        CompletableFuture<ByteString> reply = new CompletableFuture<>();
        record(serviceHash, methodId, withCiid, null, body, reply);
        return reply;
    }

    @Override
    public <Q extends Message> void on(ServiceMethod<Q, ?> method, Consumer<Q> handler) {
        handlers.put(key(method.serviceHash(), method.methodId()), body -> {
            try {
                handler.accept(method.requestParser().parseFrom(body));
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public void onRaw(int serviceHash, int methodId, Consumer<ByteString> handler) {
        handlers.put(key(serviceHash, methodId), handler);
    }

    @Override
    public void setCiid(String ciid) {
        this.ciid = ciid;
    }

    @Override
    public void onClose(Consumer<Throwable> listener) {
        closeListeners.add(listener);
    }

    @Override
    public Executor loop() {
        return Runnable::run;
    }

    @Override
    public void close() {
        drop(null);
    }

    // --- test side ---

    /** Answers the oldest unanswered call of this method. */
    @SuppressWarnings("unchecked")
    <R extends Message> void reply(ServiceMethod<?, R> method, R response) {
        ((CompletableFuture<R>) pending(method.serviceHash(), method.methodId()).reply()).complete(response);
    }

    /** Fails the oldest unanswered call of this method with a BGS status. */
    void fail(ServiceMethod<?, ?> method, int status) {
        pending(method.serviceHash(), method.methodId()).reply().completeExceptionally(new BgsRpcException(status));
    }

    @SuppressWarnings("unchecked")
    void replyRaw(int serviceHash, int methodId, ByteString body) {
        ((CompletableFuture<ByteString>) pending(serviceHash, methodId).reply()).complete(body);
    }

    void failRaw(int serviceHash, int methodId, int status) {
        pending(serviceHash, methodId).reply().completeExceptionally(new BgsRpcException(status));
    }

    void push(ServiceMethod<?, ?> method, Message notification) {
        pushRaw(method.serviceHash(), method.methodId(), notification.toByteString());
    }

    void pushRaw(int serviceHash, int methodId, ByteString body) {
        Consumer<ByteString> handler = handlers.get(key(serviceHash, methodId));
        if (handler != null) {
            handler.accept(body);
        }
    }

    /** The session ends as on a transport error. */
    void drop(Throwable cause) {
        if (closed) {
            return;
        }
        closed = true;
        List.copyOf(closeListeners).forEach(l -> l.accept(cause));
    }

    /** The typed requests sent for this method, in order. */
    @SuppressWarnings("unchecked")
    <Q extends Message> List<Q> requests(ServiceMethod<Q, ?> method) {
        return sent.stream().filter(s -> s.serviceHash() == method.serviceHash() && s.methodId() == method.methodId())
                .map(s -> (Q) s.request()).toList();
    }

    List<Sent> sentTo(int serviceHash, int methodId) {
        return sent.stream().filter(s -> s.serviceHash() == serviceHash && s.methodId() == methodId).toList();
    }

    boolean hasPending(ServiceMethod<?, ?> method) {
        return sentTo(method.serviceHash(), method.methodId()).stream()
                .anyMatch(s -> s.reply() != null && !s.reply().isDone());
    }

    private Sent pending(int serviceHash, int methodId) {
        return sentTo(serviceHash, methodId).stream().filter(s -> s.reply() != null && !s.reply().isDone())
                .findFirst().orElseThrow(() -> new AssertionError("No pending call 0x"
                        + Integer.toHexString(serviceHash).toUpperCase() + "." + methodId));
    }

    private void record(int serviceHash, int methodId, boolean ciid, Message request, ByteString body,
                        CompletableFuture<?> reply) {
        timeline.add("call 0x" + Integer.toHexString(serviceHash).toUpperCase() + "." + methodId);
        sent.add(new Sent(serviceHash, methodId, ciid && !this.ciid.isEmpty(), request, body, reply));
    }

    /** Same rule as BgsSession: the ciid goes on friends and presence calls. */
    private static boolean needsCiid(ServiceMethod<?, ?> method) {
        return method.service().contains("friends") || method.service().contains("presence");
    }

    private static long key(int serviceHash, int methodId) {
        return ((long) serviceHash << 32) | (methodId & 0xFFFFFFFFL);
    }
}
