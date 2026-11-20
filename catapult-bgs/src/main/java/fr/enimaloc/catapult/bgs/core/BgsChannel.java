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

    /** As {@link #callRaw}, but the exchange never reaches the {@link WireTap}. */
    CompletableFuture<ByteString> callRawUnobserved(int serviceHash, int methodId, ByteString body, boolean withCiid);

    /**
     * Sends a call nobody answers, never handed to the {@link WireTap}; completes once written, fails when the
     * session is closed.
     */
    CompletableFuture<Void> callRawNoReplyUnobserved(int serviceHash, int methodId, ByteString body, boolean withCiid);

    <Q extends Message> void on(ServiceMethod<Q, ?> method, Consumer<Q> handler);

    void onRaw(int serviceHash, int methodId, Consumer<ByteString> handler);

    void setCiid(String ciid);

    /** Called once when the session ends; the cause is null for a local {@link #close()}. */
    void onClose(Consumer<Throwable> listener);

    /**
     * Hands every RPC exchange to {@code tap} from now on, or to nobody when null. A no-op for channels that
     * cannot be observed.
     */
    default void setWireTap(WireTap tap) {
    }

    /** The single-thread executor that carries the session: callbacks run on it. */
    Executor loop();

    @Override
    void close();
}
