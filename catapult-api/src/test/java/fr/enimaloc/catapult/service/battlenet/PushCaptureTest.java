package fr.enimaloc.catapult.service.battlenet;

import bgs.protocol.RpcProto.Header;
import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.core.WireExchange;
import fr.enimaloc.catapult.bgs.core.WireTap;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PushCaptureTest {

    private static final Duration WINDOW = Duration.ofSeconds(5);

    private Instant now = Instant.parse("2026-11-20T12:00:00Z");
    private final PushCapture capture = new PushCapture(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    private static WireExchange exchange(boolean local, int hash, int method, String body) {
        Header header = Header.newBuilder().setServiceId(0).setServiceHash(hash).setMethodId(method).setToken(1).build();
        return new WireExchange(local, Instant.EPOCH, 0, header, ByteString.copyFromUtf8(body), null, null, null, null);
    }

    @Test
    void arm_twice_isRefused() {
        assertThat(capture.arm(WINDOW)).isTrue();
        assertThat(capture.arm(WINDOW)).isFalse();
    }

    @Test
    void release_thenArm_isAccepted() {
        capture.arm(WINDOW);
        capture.release();

        assertThat(capture.arm(WINDOW)).isTrue();
    }

    @Test
    void idle_wantsNothing() {
        assertThat(capture.wants()).isFalse();
    }

    @Test
    void capture_keepsOnlyServerCallsInTheWindow() {
        capture.arm(WINDOW);
        capture.onExchange(exchange(true, 0x138D200C, 6, "ours"));
        capture.onExchange(exchange(false, 0xF4729709, 1, "push"));
        now = now.plusSeconds(6);
        assertThat(capture.wants()).isFalse();
        capture.onExchange(exchange(false, 0xF4729709, 1, "late"));

        List<PushCapture.CapturedPush> pushes = capture.release();

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.serviceHash()).isEqualTo(0xF4729709);
            assertThat(p.methodId()).isEqualTo(1);
            assertThat(p.body().toStringUtf8()).isEqualTo("push");
            assertThat(p.at()).isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
        });
    }

    @Test
    void capture_skipsKeepAliveAndEcho() {
        capture.arm(WINDOW);
        capture.onExchange(exchange(false, 0x65446991, 5, "keep-alive"));
        capture.onExchange(exchange(false, 0x65446991, 3, "echo"));
        capture.onExchange(exchange(false, 0x65446991, 4, "force-disconnect"));

        assertThat(capture.release()).extracting(p -> p.body().toStringUtf8()).containsExactly("force-disconnect");
    }

    @Test
    void capture_unknownService_keepsHex() {
        capture.arm(WINDOW);
        capture.onExchange(exchange(false, 0xBCA72135, 1, "raw"));

        assertThat(capture.release()).singleElement()
                .satisfies(p -> assertThat(p.serviceHash()).isEqualTo(0xBCA72135));
    }

    @Test
    void release_disarms() {
        capture.arm(WINDOW);
        capture.release();
        capture.onExchange(exchange(false, 0xF4729709, 1, "after"));

        assertThat(capture.wants()).isFalse();
        capture.arm(WINDOW);
        assertThat(capture.release()).isEmpty();
    }

    @Test
    void teeWireTap_stillFeedsTheRecorderWhenTheCaptureIsIdle() {
        List<WireExchange> recorded = new ArrayList<>();
        WireTap recorder = new WireTap() {
            @Override
            public boolean wants() {
                return true;
            }

            @Override
            public void onExchange(WireExchange exchange) {
                recorded.add(exchange);
            }
        };
        TeeWireTap tee = new TeeWireTap(recorder, capture);

        assertThat(tee.wants()).isTrue();
        tee.onExchange(exchange(false, 0xF4729709, 1, "push"));

        assertThat(recorded).hasSize(1);
        capture.arm(WINDOW);
        assertThat(capture.release()).isEmpty();
    }

    @Test
    void teeWireTap_aThrowingTapDoesNotStopTheOthers() {
        WireTap throwing = new WireTap() {
            @Override
            public boolean wants() {
                return true;
            }

            @Override
            public void onExchange(WireExchange exchange) {
                throw new IllegalStateException("boom");
            }
        };
        TeeWireTap tee = new TeeWireTap(throwing, capture);
        capture.arm(WINDOW);

        tee.onExchange(exchange(false, 0xF4729709, 1, "push"));

        assertThat(capture.release()).hasSize(1);
    }

    @Test
    void teeWireTap_wantsNothingWhenNobodyDoes() {
        assertThat(new TeeWireTap(capture).wants()).isFalse();
    }
}
