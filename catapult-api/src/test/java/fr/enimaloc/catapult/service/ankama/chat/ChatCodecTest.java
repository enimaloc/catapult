package fr.enimaloc.catapult.service.ankama.chat;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Event;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Outer;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Payload;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Request;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Response;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.EndpointProperties;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.Invite;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.User;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCodecTest {

    private static final long STREAMER = 190682239L;
    private static final long BOT = 190683866L;

    @FunctionalInterface
    private interface Writer {
        void accept(CodedOutputStream out) throws IOException;
    }

    private static byte[] proto(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            writer.accept(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] user(long id, String name, String tag) {
        return proto(o -> {
            o.writeInt64(1, id);
            o.writeString(2, name);
            o.writeString(3, tag);
        });
    }

    private static byte[] invite(byte[] inviter, byte[] recipient) {
        return proto(o -> {
            o.writeByteArray(1, inviter);
            o.writeByteArray(2, recipient);
        });
    }

    private static byte[] endpoint(int applicationId, List<String> activities, Map<String, String> metadata) {
        return proto(o -> {
            o.writeInt32(1, applicationId);
            for (String activity : activities) {
                o.writeString(2, activity);
            }
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                o.writeByteArray(3, proto(e -> {
                    e.writeString(1, entry.getKey());
                    e.writeString(2, entry.getValue());
                }));
            }
        });
    }

    @Test
    void outerFrame_roundTrips_lengthIncludesTheFiveByteHeader() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ChatFrames.write(out, ChatFrames.KIND_DATA, new byte[]{1, 2, 3});

        assertThat(out.toByteArray()).containsExactly(0, 0, 0, 8, 0, 1, 2, 3);
        Outer outer = ChatFrames.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(outer.kind()).isEqualTo(ChatFrames.KIND_DATA);
        assertThat(outer.body()).containsExactly(1, 2, 3);
    }

    @Test
    void pingIsAnEmptyFiveByteFrame() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ChatFrames.write(out, ChatFrames.KIND_PING, new byte[0]);

        assertThat(out.toByteArray()).containsExactly(0, 0, 0, 5, 1);
    }

    @Test
    void outerFrame_onClosedStream_throwsEof() {
        assertThatThrownBy(() -> ChatFrames.read(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(EOFException.class);
        assertThatThrownBy(() -> ChatFrames.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 8, 0, 1})))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void requestFrame_roundTrips() throws IOException {
        Payload payload = ChatMessages.invite(BOT, "enimaloc", "6094").toPayload();
        Request request = new Request(3, payload);

        ChatFrames.Frame decoded = ChatFrames.decode(ChatFrames.encode(request));

        assertThat(decoded).isInstanceOf(Request.class);
        Request back = (Request) decoded;
        assertThat(back.correlationId()).isEqualTo(3);
        assertThat(back.payload().id()).isEqualTo(ChatMessages.CREATE_FRIEND_INVITE_CMD);
        assertThat(back.payload().data()).isEqualTo(payload.data());
    }

    @Test
    void responseFrame_withAndWithoutPayload() throws IOException {
        byte[] withPayload = proto(o -> o.writeByteArray(2, proto(r -> {
            r.writeInt32(1, 7);
            r.writeInt32(2, 1);
            r.writeByteArray(3, proto(p -> {
                p.writeInt32(1, ChatMessages.FRIEND_LIST);
                p.writeByteArray(2, new byte[]{9});
            }));
        })));
        byte[] withoutPayload = proto(o -> o.writeByteArray(2, proto(r -> {
            r.writeInt32(1, 8);
            r.writeInt32(2, 1);
        })));

        Response first = (Response) ChatFrames.decode(withPayload);
        Response second = (Response) ChatFrames.decode(withoutPayload);

        assertThat(first.correlationId()).isEqualTo(7);
        assertThat(first.status()).isEqualTo(1);
        assertThat(first.payload().id()).isEqualTo(ChatMessages.FRIEND_LIST);
        assertThat(first.payload().data()).containsExactly(9);
        assertThat(second.correlationId()).isEqualTo(8);
        assertThat(second.payload()).isNull();
    }

    @Test
    void eventFrame_decodesTheNegativePayloadId() throws IOException {
        byte[] frame = proto(o -> o.writeByteArray(3, proto(p -> {
            p.writeInt32(1, ChatMessages.USER_PRESENCE_UPDATED_EVT);
            p.writeByteArray(2, new byte[]{1, 2});
        })));

        ChatFrames.Frame decoded = ChatFrames.decode(frame);

        assertThat(decoded).isInstanceOf(Event.class);
        assertThat(((Event) decoded).payload().id()).isEqualTo(-128858966);
        assertThat(((Event) decoded).payload().data()).containsExactly(1, 2);
    }

    @Test
    void inviteCommand_encodesNameAndTagInField4() throws IOException {
        Payload payload = ChatMessages.invite(BOT, "enimaloc", "6094").toPayload();

        CodedInputStream in = CodedInputStream.newInstance(payload.data());
        long inviter = 0;
        String name = null;
        String tag = null;
        boolean sawField2 = false;
        int tagNumber;
        while ((tagNumber = in.readTag()) != 0) {
            switch (tagNumber >>> 3) {
                case 1 -> inviter = in.readInt64();
                case 2 -> {
                    sawField2 = true;
                    in.readInt64();
                }
                case 4 -> {
                    CodedInputStream nested = CodedInputStream.newInstance(in.readByteArray());
                    int nestedTag;
                    while ((nestedTag = nested.readTag()) != 0) {
                        switch (nestedTag >>> 3) {
                            case 1 -> name = nested.readString();
                            case 2 -> tag = nested.readString();
                            default -> nested.skipField(nestedTag);
                        }
                    }
                }
                default -> in.skipField(tagNumber);
            }
        }

        assertThat(payload.id()).isEqualTo(ChatMessages.CREATE_FRIEND_INVITE_CMD);
        assertThat(inviter).isEqualTo(BOT);
        assertThat(name).isEqualTo("enimaloc");
        assertThat(tag).isEqualTo("6094");
        assertThat(sawField2).isFalse();
    }

    @Test
    void simpleCommands_carryTheirIdsAndFields() throws IOException {
        Payload list = ChatMessages.listFriends(BOT).toPayload();
        Payload invites = ChatMessages.listInvites(BOT).toPayload();
        Payload deleteInvite = ChatMessages.deleteInvite(BOT, STREAMER, ChatMessages.INVITE_CANCEL).toPayload();
        Payload deleteFriend = ChatMessages.deleteFriend(BOT, STREAMER).toPayload();

        assertThat(list.id()).isEqualTo(ChatMessages.LIST_USER_FRIENDS_CMD);
        assertThat(invites.id()).isEqualTo(ChatMessages.LIST_USER_FRIEND_INVITES_CMD);
        assertThat(deleteInvite.id()).isEqualTo(ChatMessages.DELETE_FRIEND_INVITE_CMD);
        assertThat(deleteFriend.id()).isEqualTo(ChatMessages.DELETE_USER_FRIEND_CMD);
        assertThat(CodedInputStream.newInstance(list.data()).readTag()).isEqualTo(1 << 3);
        CodedInputStream in = CodedInputStream.newInstance(deleteInvite.data());
        in.readTag();
        assertThat(in.readInt64()).isEqualTo(BOT);
        in.readTag();
        assertThat(in.readInt64()).isEqualTo(STREAMER);
        in.readTag();
        assertThat(in.readInt32()).isEqualTo(2);
    }

    @Test
    void endpointsEvent_fromTheSpike_givesWakfuThenLauncher() throws IOException {
        byte[] data = proto(o -> {
            o.writeInt64(1, STREAMER);
            o.writeByteArray(2, endpoint(3, List.of(), Map.of()));
            o.writeByteArray(2, endpoint(102, List.of("IN_LAUNCHER"), Map.of()));
        });

        ChatMessages.Event event = ChatMessages.event(
                new Payload(ChatMessages.USER_ENDPOINT_PROPERTIES_UPDATED_EVT, data));

        assertThat(event).isEqualTo(new ChatMessages.EndpointsUpdated(STREAMER, List.of(
                new EndpointProperties(3, List.of(), Map.of()),
                new EndpointProperties(102, List.of("IN_LAUNCHER"), Map.of()))));
    }

    @Test
    void endpointsEvent_keepsMetadata() throws IOException {
        byte[] data = proto(o -> {
            o.writeInt64(1, STREAMER);
            o.writeByteArray(2, endpoint(1, List.of(), Map.of("SERVER_NAME", "Imagiro")));
        });

        ChatMessages.EndpointsUpdated event = (ChatMessages.EndpointsUpdated) ChatMessages.event(
                new Payload(ChatMessages.USER_ENDPOINT_PROPERTIES_UPDATED_EVT, data));

        assertThat(event.properties()).singleElement().satisfies(p -> {
            assertThat(p.applicationId()).isEqualTo(1);
            assertThat(p.metadata()).containsExactly(Map.entry("SERVER_NAME", "Imagiro"));
        });
    }

    @Test
    void presenceEvent_withoutField2_isOnline() throws IOException {
        byte[] online = proto(o -> o.writeInt64(1, STREAMER));
        byte[] offline = proto(o -> {
            o.writeInt64(1, STREAMER);
            o.writeInt32(2, ChatMessages.PRESENCE_OFFLINE);
        });

        assertThat(ChatMessages.event(new Payload(ChatMessages.USER_PRESENCE_UPDATED_EVT, online)))
                .isEqualTo(new ChatMessages.PresenceUpdated(STREAMER, ChatMessages.PRESENCE_ONLINE));
        assertThat(ChatMessages.event(new Payload(ChatMessages.USER_PRESENCE_UPDATED_EVT, offline)))
                .isEqualTo(new ChatMessages.PresenceUpdated(STREAMER, ChatMessages.PRESENCE_OFFLINE));
    }

    @Test
    void inviteCreated_andInviteDeleted() throws IOException {
        Invite expected = new Invite(new User(BOT, "Catapult-1", "5091"), new User(STREAMER, "enimaloc", "6094"));
        byte[] invite = invite(user(BOT, "Catapult-1", "5091"), user(STREAMER, "enimaloc", "6094"));

        ChatMessages.Event created = ChatMessages.event(new Payload(ChatMessages.FRIEND_INVITE_CREATED_EVT,
                proto(o -> o.writeByteArray(1, invite))));
        ChatMessages.Event accepted = ChatMessages.event(new Payload(ChatMessages.FRIEND_INVITE_DELETED_EVT,
                proto(o -> o.writeByteArray(1, invite))));
        ChatMessages.Event rejected = ChatMessages.event(new Payload(ChatMessages.FRIEND_INVITE_DELETED_EVT,
                proto(o -> {
                    o.writeByteArray(1, invite);
                    o.writeInt32(2, ChatMessages.INVITE_REJECT);
                })));

        assertThat(created).isEqualTo(new ChatMessages.InviteCreated(expected));
        assertThat(accepted).isEqualTo(new ChatMessages.InviteDeleted(expected, ChatMessages.INVITE_ACCEPT));
        assertThat(rejected).isEqualTo(new ChatMessages.InviteDeleted(expected, ChatMessages.INVITE_REJECT));
    }

    @Test
    void friendEvents_decodeTheirValues() throws IOException {
        byte[] friend = proto(o -> {
            o.writeByteArray(1, user(STREAMER, "enimaloc", "6094"));
            o.writeString(3, "a note");
        });
        byte[] created = proto(o -> {
            o.writeInt64(1, BOT);
            o.writeByteArray(2, friend);
        });
        byte[] deleted = proto(o -> {
            o.writeInt64(1, BOT);
            o.writeInt64(2, STREAMER);
        });

        assertThat(ChatMessages.event(new Payload(ChatMessages.USER_FRIEND_CREATED_EVT, created)))
                .isEqualTo(new ChatMessages.FriendCreated(BOT, new User(STREAMER, "enimaloc", "6094")));
        assertThat(ChatMessages.event(new Payload(ChatMessages.USER_FRIEND_DELETED_EVT, deleted)))
                .isEqualTo(new ChatMessages.FriendDeleted(BOT, STREAMER));
    }

    @Test
    void friendList_andInviteList_decodeTheirValues() throws IOException {
        byte[] friends = proto(o -> {
            o.writeByteArray(1, proto(f -> f.writeByteArray(1, user(STREAMER, "enimaloc", "6094"))));
            o.writeByteArray(1, proto(f -> f.writeByteArray(1, user(5, "other", "1"))));
        });
        byte[] invites = proto(o -> o.writeByteArray(1,
                invite(user(BOT, "Catapult-1", "5091"), user(STREAMER, "enimaloc", "6094"))));

        assertThat(ChatMessages.friendList(new Payload(ChatMessages.FRIEND_LIST, friends)))
                .containsExactly(new User(STREAMER, "enimaloc", "6094"), new User(5, "other", "1"));
        assertThat(ChatMessages.inviteList(new Payload(ChatMessages.FRIEND_INVITE_LIST, invites)))
                .containsExactly(new Invite(new User(BOT, "Catapult-1", "5091"),
                        new User(STREAMER, "enimaloc", "6094")));
        assertThat(ChatMessages.friendList(new Payload(ChatMessages.FRIEND_LIST, new byte[0]))).isEmpty();
    }

    @Test
    void unknownPayloadId_isUnknown_notAnError() throws IOException {
        assertThat(ChatMessages.event(new Payload(42, new byte[]{1, 2, 3})))
                .isEqualTo(new ChatMessages.Unknown(42));
    }

    @Test
    void int64_negativeValues_roundTrip() throws IOException {
        byte[] data = proto(o -> {
            o.writeInt64(1, -7L);
            o.writeInt64(2, -9L);
        });

        assertThat(ChatMessages.event(new Payload(ChatMessages.USER_FRIEND_DELETED_EVT, data)))
                .isEqualTo(new ChatMessages.FriendDeleted(-7L, -9L));
        CodedInputStream in = CodedInputStream.newInstance(ChatMessages.listFriends(-7L).toPayload().data());
        in.readTag();
        assertThat(in.readInt64()).isEqualTo(-7L);
    }
}
