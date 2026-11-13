package fr.enimaloc.catapult.service.battlenet;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import fr.enimaloc.catapult.bgs.core.BgsChannel;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * An in-memory {@link BgsChannel}: no socket. Calls stay pending until the test answers them, notifications are
 * pushed by the test, and the loop runs every task on the calling thread.
 */
final class FakeBgsChannel implements BgsChannel {

    private final Map<String, List<CompletableFuture<Object>>> pending = new HashMap<>();
    private final Map<String, Consumer<ByteString>> handlers = new HashMap<>();
    private final List<Consumer<Throwable>> closeListeners = new ArrayList<>();
    boolean closed;

    @Override
    @SuppressWarnings("unchecked")
    public <R extends Message> CompletableFuture<R> call(ServiceMethod<?, R> method, Message request) {
        CompletableFuture<Object> reply = new CompletableFuture<>();
        pending.computeIfAbsent(key(method.serviceHash(), method.methodId()), k -> new ArrayList<>()).add(reply);
        return (CompletableFuture<R>) (CompletableFuture<?>) reply;
    }

    @Override
    public void callNoReply(ServiceMethod<?, ?> method, Message request) {
    }

    @Override
    public CompletableFuture<ByteString> callRaw(int serviceHash, int methodId, ByteString body, boolean withCiid) {
        return new CompletableFuture<>();
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
        if (closed) {
            return;
        }
        closed = true;
        List.copyOf(closeListeners).forEach(l -> l.accept(null));
    }

    /** Answers the oldest pending call of this method. */
    <R extends Message> void reply(ServiceMethod<?, R> method, R response) {
        pending.get(key(method.serviceHash(), method.methodId())).removeFirst().complete(response);
    }

    void push(ServiceMethod<?, ?> method, Message notification) {
        Consumer<ByteString> handler = handlers.get(key(method.serviceHash(), method.methodId()));
        if (handler != null) {
            handler.accept(notification.toByteString());
        }
    }

    private static String key(int serviceHash, int methodId) {
        return serviceHash + ":" + methodId;
    }
}
