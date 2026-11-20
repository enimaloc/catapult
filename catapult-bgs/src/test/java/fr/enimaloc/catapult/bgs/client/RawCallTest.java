package fr.enimaloc.catapult.bgs.client;

import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin's free call: unobserved, refused before the session is ready, status propagated. */
class RawCallTest {

    private static final ByteString BODY = ByteString.copyFromUtf8("q");

    @Test
    void rawCall_beforeReady_failsAtOnce() {
        ClientHarness h = new ClientHarness().start();
        int before = h.session.sent.size();

        CompletableFuture<ByteString> f = h.client.rawCall(0x138D200C, 6, BODY, true, true);

        assertThat(f).isCompletedExceptionally();
        assertThat(f.exceptionNow()).isInstanceOf(IllegalStateException.class);
        assertThat(h.session.sent).hasSize(before);
    }

    @Test
    void rawCall_forwardsHashMethodBodyAndCiid() {
        ClientHarness h = new ClientHarness().ready();

        CompletableFuture<ByteString> f = h.client.rawCall(0x138D200C, 6, BODY, true, true);

        FakeSession.Sent sent = h.session.sentTo(0x138D200C, 6).getLast();
        assertThat(sent.body()).isEqualTo(BODY);
        assertThat(sent.ciid()).isTrue();
        assertThat(sent.observed()).isFalse();
        h.session.replyRaw(0x138D200C, 6, ByteString.copyFromUtf8("r"));
        assertThat(f.join().toStringUtf8()).isEqualTo("r");
    }

    @Test
    void rawCall_nonZeroStatus_failsWithTheStatus() {
        ClientHarness h = new ClientHarness().ready();

        CompletableFuture<ByteString> f = h.client.rawCall(0x138D200C, 6, BODY, false, true);
        h.session.failRaw(0x138D200C, 6, 7);

        assertThat(f.exceptionNow()).isInstanceOfSatisfying(BgsRpcException.class,
                e -> assertThat(e.status()).isEqualTo(7));
    }

    @Test
    void rawCall_noReply_completesEmpty() {
        ClientHarness h = new ClientHarness().ready();

        CompletableFuture<ByteString> f = h.client.rawCall(0x65446991, 5, ByteString.EMPTY, false, false);

        assertThat(f.join()).isEqualTo(ByteString.EMPTY);
        FakeSession.Sent sent = h.session.sentTo(0x65446991, 5).getLast();
        assertThat(sent.reply()).isNull();
        assertThat(sent.observed()).isFalse();
    }
}
