package fr.enimaloc.catapult.service.battlenet;

import bgs.protocol.RpcProto.Header;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldKey;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldUpdate;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.UpdateRequest;
import bgs.protocol.v2.AttributeTypes.Variant;
import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.client.BgsClient;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.bgs.core.WireExchange;
import fr.enimaloc.catapult.common.dto.admin.BgsCallRequest;
import fr.enimaloc.catapult.common.dto.admin.BgsCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BattleNetRawCallServiceTest {

    private static final String PRESENCE = "bgs.protocol.presence.v2.client.PresenceService";
    private static final String UPDATE_TEXT = "updates { key { group: 1 field: 12 } value { bool_value: true } }";

    private final UUID bot = UUID.randomUUID();
    private final BattleNetSessionManager sessions = mock(BattleNetSessionManager.class);
    private final BgsClient client = mock(BgsClient.class);
    private Instant now = Instant.parse("2026-11-20T12:00:00Z");
    private Duration slept = Duration.ZERO;
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final PushCapture capture = new PushCapture(clock);
    /** Called with the capture armed, as the reply arrives: pushes the test wants in the window. */
    private Runnable duringCall = () -> {};
    private CompletableFuture<ByteString> reply = CompletableFuture.completedFuture(ByteString.EMPTY);
    private BattleNetRawCallService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(sessions.captureOf(bot)).thenReturn(Optional.of(capture));
        when(sessions.withClient(eq(bot), any())).thenAnswer(inv -> {
            duringCall.run();
            return ((Function<BgsClient, CompletableFuture<ByteString>>) inv.getArgument(1)).apply(client);
        });
        when(client.rawCall(anyInt(), anyInt(), any(), anyBoolean(), anyBoolean())).thenAnswer(inv -> reply);
        service = new BattleNetRawCallService(sessions, clock, d -> {
            slept = slept.plus(d);
            now = now.plus(d);
        });
    }

    private static BgsCallRequest known(String service, String method, String text) {
        return new BgsCallRequest(service, method, text, null, null, null, null);
    }

    private static BgsCallRequest raw(String hash, int methodId, String hex) {
        return new BgsCallRequest(null, null, null, hash, methodId, hex, true);
    }

    private static HttpStatus statusOf(Throwable e) {
        return HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
    }

    @Test
    void catalog_listsPresenceUpdateWithItsHash() {
        assertThat(service.catalog()).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo(PRESENCE);
            assertThat(s.hash()).isEqualTo("0x138D200C");
            assertThat(s.methods()).anySatisfy(m -> {
                assertThat(m.name()).isEqualTo("Update");
                assertThat(m.id()).isEqualTo(6);
                assertThat(m.noResponse()).isFalse();
            });
        });
    }

    @Test
    void call_knownMethod_encodesTextAndDecodesReply() {
        BgsCallResult result = service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin");

        ByteString expected = UpdateRequest.newBuilder()
                .addUpdates(PresenceFieldUpdate.newBuilder()
                        .setKey(PresenceFieldKey.newBuilder().setGroup(1).setField(12))
                        .setValue(Variant.newBuilder().setBoolValue(true)))
                .build().toByteString();
        verify(client).rawCall(0x138D200C, 6, expected, true, true);
        assertThat(result.status()).isZero();
        assertThat(result.replyText()).isEmpty();
        assertThat(result.error()).isNull();
    }

    @Test
    void call_waitsForTheEndOfTheWindow() {
        service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin");

        assertThat(slept).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void call_hexMode_unknownService_returnsHex() {
        reply = CompletableFuture.completedFuture(ByteString.copyFrom(new byte[]{0x0A, 0x01}));

        BgsCallResult result = service.call(bot, raw("0xBCA72135", 1, "08 00"), "admin");

        verify(client).rawCall(0xBCA72135, 1, ByteString.copyFrom(new byte[]{0x08, 0x00}), true, true);
        assertThat(result.replyText()).isNull();
        assertThat(result.replyHex()).isEqualTo("0a01");
    }

    @Test
    void call_hexWithSpaces_isAccepted() {
        service.call(bot, raw("BCA72135", 1, " 0x08 00\n01 "), "admin");

        verify(client).rawCall(0xBCA72135, 1, ByteString.copyFrom(new byte[]{0x08, 0x00, 0x01}), true, true);
    }

    @Test
    void call_badHex_is400() {
        assertThatThrownBy(() -> service.call(bot, raw("0xBCA72135", 1, "0x0"), "admin"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.call(bot, raw("0xBCA72135", 1, "zz"), "admin"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.call(bot, raw("nope", 1, ""), "admin"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.call(bot, new BgsCallRequest(null, null, null, "0x1", null, "", null), "admin"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void call_invalidText_is400WithPosition() {
        assertThatThrownBy(() -> service.call(bot, known(PRESENCE, "Update", "updates { nope: 1 }"), "admin"))
                .satisfies(e -> {
                    assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ResponseStatusException) e).getReason()).contains("1:");
                });
    }

    @Test
    void call_unknownMethodName_is400() {
        assertThatThrownBy(() -> service.call(bot, known(PRESENCE, "Nope", ""), "admin"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void call_nonZeroStatus_isReturnedNotThrown() {
        reply = CompletableFuture.failedFuture(new BgsRpcException(7));

        BgsCallResult result = service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin");

        assertThat(result.status()).isEqualTo(7);
        assertThat(result.error()).isNull();
    }

    @Test
    void call_noSession_is409() {
        when(sessions.captureOf(bot)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void call_sessionNotReady_is409AndReleasesTheBot() {
        reply = CompletableFuture.failedFuture(new IllegalStateException("The BGS session is not ready"));

        assertThatThrownBy(() -> service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        assertThat(capture.arm(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void call_whileAnotherRuns_is409() {
        capture.arm(Duration.ofSeconds(5));

        assertThatThrownBy(() -> service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin"))
                .satisfies(e -> {
                    assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ResponseStatusException) e).getReason()).contains("already running");
                });
    }

    @Test
    void call_noResponseMethod_doesNotWait() {
        BgsCallResult result = service.call(bot,
                known("bgs.protocol.connection.v1.ConnectionService", "KeepAlive", ""), "admin");

        verify(client).rawCall(0x65446991, 5, ByteString.EMPTY, false, false);
        assertThat(result.status()).isNull();
        assertThat(result.error()).isNull();
    }

    @Test
    void call_sessionClosed_reportsErrorAndReleasesTheBot() {
        reply = CompletableFuture.failedFuture(new IOException("Session closed"));

        BgsCallResult result = service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin");

        assertThat(result.status()).isNull();
        assertThat(result.error()).contains("Session closed");
        reply = CompletableFuture.completedFuture(ByteString.EMPTY);
        assertThat(service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin").status()).isZero();
    }

    @Test
    void call_timeout_reportsTimeout() {
        reply = new CompletableFuture<>();
        service = new BattleNetRawCallService(sessions, clock, d -> now = now.plus(d), Duration.ofMillis(10));

        BgsCallResult result = service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin");

        assertThat(result.error()).isEqualTo("timeout");
        assertThat(capture.arm(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void call_includesPushesOfTheWindow() {
        Header known = Header.newBuilder().setServiceId(0).setServiceHash(0xF4729709).setMethodId(1).setToken(3).build();
        Header unknown = Header.newBuilder().setServiceId(0).setServiceHash(0xBCA72135).setMethodId(1).setToken(4).build();
        duringCall = () -> {
            capture.onExchange(new WireExchange(false, now, 0, known, ByteString.EMPTY, null, null, null, null));
            capture.onExchange(new WireExchange(false, now, 0, unknown, ByteString.copyFrom(new byte[]{0x08}), null,
                    null, null, null));
        };

        BgsCallResult result = service.call(bot, known(PRESENCE, "Update", UPDATE_TEXT), "admin");

        assertThat(result.pushes()).hasSize(2);
        assertThat(result.pushes().get(0).service()).isEqualTo("bgs.protocol.presence.v2.PresenceListener");
        assertThat(result.pushes().get(0).method()).isEqualTo("OnPresenceStateUpdated");
        assertThat(result.pushes().get(0).text()).isEmpty();
        assertThat(result.pushes().get(1).service()).isEqualTo("0xBCA72135");
        assertThat(result.pushes().get(1).method()).isEqualTo("1");
        assertThat(result.pushes().get(1).text()).isNull();
        assertThat(result.pushes().get(1).hex()).isEqualTo("08");
    }
}
