package fr.enimaloc.catapult.bgs.core;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * What a BGS client needs from a session, with no transport detail, so it can be faked in tests.
 * Handlers and future completions run on {@link #loop()}.
 */
public interface BgsChannel extends AutoCloseable {

    <R extends Message> CompletableFuture<R> call(ServiceMethod<?, R> method, Message request);

    void callNoReply(ServiceMethod<?, ?> method, Message request);

    CompletableFuture<ByteString> callRaw(int serviceHash, int methodId, ByteString body, boolean withCiid);

    <Q extends Message> void on(ServiceMethod<Q, ?> method, Consumer<Q> handler);

    void onRaw(int serviceHash, int methodId, Consumer<ByteString> handler);

    void setCiid(String ciid);

    /** Called once when the session ends; the cause is null for a local {@link #close()}. */
    void onClose(Consumer<Throwable> listener);

    /** The single-thread executor that carries the session: callbacks run on it. */
    Executor loop();

    @Override
    void close();
}
