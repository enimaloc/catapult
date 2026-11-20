package fr.enimaloc.catapult.service.ankama;

import com.google.protobuf.CodedOutputStream;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Payload;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;
import fr.enimaloc.catapult.service.ankama.chat.ChatWireExchange;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.debug.SessionDebug;
import fr.enimaloc.catapult.service.debug.TestSessionDebug;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The chat frames a bot exchanges are recorded for the member in debug mode they name, and only those. */
class AnkamaWireRecorderTest {

    private static final UUID BOT = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

    private final TestSessionDebug watched = new TestSessionDebug("ankama", BOT, AnkamaDebugKeys.user(42L),
            AnkamaDebugKeys.nameTag("Streamer", "1234"));
    private final AnkamaWireRecorder recorder = new AnkamaWireRecorder(BOT, watched.debug);

    private interface Writer {
        void accept(CodedOutputStream out) throws IOException;
    }

    private static byte[] bytes(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            writer.accept(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] user(long userId, String name, String tag) {
        return bytes(o -> {
            o.writeInt64(1, userId);
            o.writeString(2, name);
            o.writeString(3, tag);
        });
    }

    private static byte[] presence(long userId) {
        return bytes(o -> {
            o.writeInt64(1, userId);
            o.writeInt32(2, ChatMessages.PRESENCE_OFFLINE);
        });
    }

    private static ChatWireExchange event(int id, byte[] data) {
        return new ChatWireExchange(false, AT, 0, new ChatFrames.Event(new Payload(id, data)), new byte[]{1}, null,
                null, null);
    }

    private static ChatWireExchange call(Payload request, ChatFrames.Response response, String error) {
        return new ChatWireExchange(true, AT, 4_200_000L, new ChatFrames.Request(3, request), new byte[]{1},
                response, response == null ? null : new byte[]{2}, error);
    }

    private RawExchange recorded(Direction direction, String operation) {
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(watched.recorder).record(eq(watched.member), eq(direction), raw.capture(), eq(operation), eq(false),
                eq("ankama"));
        return raw.getValue();
    }

    private void nothingRecorded() {
        verify(watched.recorder, never()).record(any(), any(), any(), any(), anyBoolean(), any());
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void recorderRecordsAPresenceEventForTheMember() {
        byte[] data = presence(42L);

        recorder.onExchange(event(ChatMessages.USER_PRESENCE_UPDATED_EVT, data));

        RawExchange raw = recorded(Direction.IN, "chat.USER_PRESENCE_UPDATED_EVT");
        assertThat(raw.method()).isEqualTo("CHAT");
        assertThat(raw.at()).isEqualTo(AT);
        assertThat(text(raw.requestBody())).isEqualTo("{\"1\":42,\"2\":1}");
        assertThat(raw.requestRaw()).isEqualTo(data);
        assertThat(raw.requestHeaders().getFirst(AnkamaWireRecorder.HEADER))
                .isEqualTo("{\"kind\":\"event\",\"payloadId\":" + ChatMessages.USER_PRESENCE_UPDATED_EVT + "}");
        assertThat(raw.status()).isNull();
        assertThat(raw.responseHeaders()).isNull();
        assertThat(raw.responseBody()).isNull();
        assertThat(raw.responseRaw()).isNull();
    }

    @Test
    void recorderMatchesAPendingInviteByNameAndTag() {
        TestSessionDebug pending = new TestSessionDebug("ankama", BOT, AnkamaDebugKeys.nameTag("Streamer", "1234"));
        Payload invite = ChatMessages.invite(7L, "Streamer", "1234").toPayload();

        new AnkamaWireRecorder(BOT, pending.debug).onExchange(call(invite, new ChatFrames.Response(3, 1, null),
                null));

        assertThat(pending.recorded(Direction.OUT, "chat.CREATE_FRIEND_INVITE_CMD"))
                .contains("\"4\":{\"1\":\"Streamer\",\"2\":\"1234\"}");
    }

    @Test
    void recorderRecordsAListRequestWithItsResponse() {
        Payload list = ChatMessages.listFriends(7L).toPayload();
        byte[] friends = bytes(o -> o.writeByteArray(1, bytes(f -> f.writeByteArray(1, user(42L, "Streamer",
                "1234")))));

        recorder.onExchange(call(list, new ChatFrames.Response(3, 1, new Payload(ChatMessages.FRIEND_LIST, friends)),
                null));

        RawExchange raw = recorded(Direction.OUT, "chat.LIST_USER_FRIENDS_CMD");
        assertThat(raw.status()).isEqualTo(1);
        assertThat(raw.durationMs()).isEqualTo(4);
        assertThat(raw.requestHeaders().getFirst(AnkamaWireRecorder.HEADER)).isEqualTo(
                "{\"kind\":\"request\",\"correlationId\":3,\"payloadId\":" + ChatMessages.LIST_USER_FRIENDS_CMD + "}");
        assertThat(raw.responseHeaders().getFirst(AnkamaWireRecorder.HEADER)).isEqualTo(
                "{\"kind\":\"response\",\"correlationId\":3,\"status\":1,\"payloadId\":" + ChatMessages.FRIEND_LIST
                        + "}");
        assertThat(text(raw.responseBody())).contains("\"2\":\"Streamer\"");
        assertThat(raw.requestRaw()).isEqualTo(list.data());
        assertThat(raw.responseRaw()).isEqualTo(friends);
    }

    @Test
    void recorderRecordsAFailedRequestWithItsError() {
        recorder.onExchange(call(ChatMessages.deleteFriend(7L, 42L).toPayload(), null, "TimeoutException"));

        RawExchange raw = recorded(Direction.OUT, "chat.DELETE_USER_FRIEND_CMD");
        assertThat(raw.error()).isEqualTo("TimeoutException");
        assertThat(raw.status()).isNull();
        assertThat(raw.responseHeaders()).isNull();
    }

    @Test
    void recorderSearchesTheRawBytesOfAnUndecodablePayload() {
        byte[] garbage = "ÿÿStreamer#1234".getBytes(StandardCharsets.ISO_8859_1);

        recorder.onExchange(event(ChatMessages.FRIEND_INVITE_CREATED_EVT, garbage));

        RawExchange raw = recorded(Direction.IN, "chat.FRIEND_INVITE_CREATED_EVT");
        assertThat(raw.requestBody()).isNull();
        assertThat(raw.requestRaw()).isEqualTo(garbage);
    }

    @Test
    void recorderIgnoresOtherMembersFrames() {
        recorder.onExchange(event(ChatMessages.USER_PRESENCE_UPDATED_EVT, presence(43L)));
        recorder.onExchange(event(ChatMessages.FRIEND_INVITE_CREATED_EVT,
                bytes(o -> o.writeByteArray(1, bytes(i -> i.writeByteArray(2, user(43L, "Other", "1234")))))));

        nothingRecorded();
    }

    @Test
    void recorderDropsUnknownPayloadIds() {
        byte[] named = "Streamer#1234".getBytes(StandardCharsets.UTF_8);

        recorder.onExchange(event(12345, presence(42L)));
        recorder.onExchange(event(ChatMessages.INVITE_REJECT, named));
        // A known response payload does not make an unknown request recorded
        recorder.onExchange(call(new Payload(12345, named),
                new ChatFrames.Response(3, 1, new Payload(ChatMessages.FRIEND_LIST, named)), null));

        nothingRecorded();
    }

    @Test
    void wantsIsFalseWhenNoMemberIsInDebug() {
        assertThat(recorder.wants()).isTrue();
        assertThat(new AnkamaWireRecorder(UUID.randomUUID(), watched.debug).wants()).isFalse();
        assertThat(new AnkamaWireRecorder(BOT, SessionDebug.OFF).wants()).isFalse();
    }

    @Test
    void aFailureIsSwallowed() {
        SessionDebug failing = mock(SessionDebug.class);
        doThrow(new IllegalStateException("boom")).when(failing).exchange(any(), any(), any(), any(), any());

        new AnkamaWireRecorder(BOT, failing).onExchange(event(ChatMessages.USER_PRESENCE_UPDATED_EVT,
                presence(42L)));

        verify(failing).exchange(eq(BOT), any(), eq(Direction.IN), any(), eq("chat.USER_PRESENCE_UPDATED_EVT"));
    }
}
