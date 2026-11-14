package fr.enimaloc.catapult.service.ankama.chat;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Payload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher chat messages Catapult uses (friends, invites, presence), hand-coded from the protocol notes. A
 * proto3 field at its default value is absent from the wire, so every decoder starts from the defaults.
 */
public final class ChatMessages {

    public static final int LIST_USER_FRIENDS_CMD = -1933682604;
    public static final int LIST_USER_FRIEND_INVITES_CMD = 669955095;
    public static final int CREATE_FRIEND_INVITE_CMD = -666416802;
    public static final int DELETE_FRIEND_INVITE_CMD = 1707706475;
    public static final int DELETE_USER_FRIEND_CMD = 926307863;

    public static final int FRIEND_LIST = -1150791598;
    public static final int FRIEND_INVITE_LIST = 425860746;

    public static final int FRIEND_INVITE_CREATED_EVT = 775219117;
    public static final int FRIEND_INVITE_DELETED_EVT = -280368044;
    public static final int USER_FRIEND_CREATED_EVT = 1611743244;
    public static final int USER_FRIEND_DELETED_EVT = -1586738187;
    public static final int USER_PRESENCE_UPDATED_EVT = -128858966;
    public static final int USER_ENDPOINT_PROPERTIES_UPDATED_EVT = 1572432262;

    /** Reasons of a {@code DeleteFriendInviteCmd}, as the launcher uses them. */
    public static final int INVITE_ACCEPT = 0;
    public static final int INVITE_REJECT = 1;
    public static final int INVITE_CANCEL = 2;

    public static final int PRESENCE_ONLINE = 0;
    public static final int PRESENCE_OFFLINE = 1;

    private ChatMessages() {
    }

    public record User(long userId, String name, String tag) {
    }

    public record Invite(User inviter, User recipient) {
    }

    public record EndpointProperties(int applicationId, List<String> activities, Map<String, String> metadata) {
    }

    // ---------- commands ----------

    public sealed interface Command {
        Payload toPayload();
    }

    public record ListFriends(long userId) implements Command {
        @Override
        public Payload toPayload() {
            return build(LIST_USER_FRIENDS_CMD, o -> o.writeInt64(1, userId));
        }
    }

    public record ListInvites(long userId) implements Command {
        @Override
        public Payload toPayload() {
            return build(LIST_USER_FRIEND_INVITES_CMD, o -> o.writeInt64(1, userId));
        }
    }

    /** Invites by {@code name#tag}; the recipient user id (field 2) stays absent. */
    public record CreateInvite(long inviterUserId, String name, String tag) implements Command {
        @Override
        public Payload toPayload() {
            return build(CREATE_FRIEND_INVITE_CMD, o -> {
                o.writeInt64(1, inviterUserId);
                o.writeByteArray(4, bytes(n -> {
                    n.writeString(1, name);
                    n.writeString(2, tag);
                }));
            });
        }
    }

    public record DeleteInvite(long inviterUserId, long recipientUserId, int reason) implements Command {
        @Override
        public Payload toPayload() {
            return build(DELETE_FRIEND_INVITE_CMD, o -> {
                o.writeInt64(1, inviterUserId);
                o.writeInt64(2, recipientUserId);
                o.writeInt32(3, reason);
            });
        }
    }

    public record DeleteFriend(long userId, long friendUserId) implements Command {
        @Override
        public Payload toPayload() {
            return build(DELETE_USER_FRIEND_CMD, o -> {
                o.writeInt64(1, userId);
                o.writeInt64(2, friendUserId);
            });
        }
    }

    public static Command listFriends(long userId) {
        return new ListFriends(userId);
    }

    public static Command listInvites(long userId) {
        return new ListInvites(userId);
    }

    public static Command invite(long inviterUserId, String name, String tag) {
        return new CreateInvite(inviterUserId, name, tag);
    }

    public static Command deleteInvite(long inviterUserId, long recipientUserId, int reason) {
        return new DeleteInvite(inviterUserId, recipientUserId, reason);
    }

    public static Command deleteFriend(long userId, long friendUserId) {
        return new DeleteFriend(userId, friendUserId);
    }

    // ---------- responses ----------

    public static List<User> friendList(Payload payload) throws IOException {
        List<User> friends = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(payload.data());
        int tag;
        while ((tag = in.readTag()) != 0) {
            if (tag >>> 3 == 1) {
                friends.add(readFriend(in.readByteArray()));
            } else {
                in.skipField(tag);
            }
        }
        return friends;
    }

    public static List<Invite> inviteList(Payload payload) throws IOException {
        List<Invite> invites = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(payload.data());
        int tag;
        while ((tag = in.readTag()) != 0) {
            if (tag >>> 3 == 1) {
                invites.add(readInvite(in.readByteArray()));
            } else {
                in.skipField(tag);
            }
        }
        return invites;
    }

    // ---------- events ----------

    public sealed interface Event {
    }

    public record InviteCreated(Invite invite) implements Event {
    }

    public record InviteDeleted(Invite invite, int reason) implements Event {
    }

    public record FriendCreated(long userId, User friend) implements Event {
    }

    public record FriendDeleted(long userId, long friendUserId) implements Event {
    }

    public record PresenceUpdated(long userId, int presence) implements Event {
    }

    public record EndpointsUpdated(long userId, List<EndpointProperties> properties) implements Event {
    }

    /** A payload Catapult does not use; not an error. */
    public record Unknown(int id) implements Event {
    }

    public static Event event(Payload payload) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(payload.data());
        return switch (payload.id()) {
            case FRIEND_INVITE_CREATED_EVT -> {
                Invite invite = new Invite(null, null);
                int tag;
                while ((tag = in.readTag()) != 0) {
                    if (tag >>> 3 == 1) {
                        invite = readInvite(in.readByteArray());
                    } else {
                        in.skipField(tag);
                    }
                }
                yield new InviteCreated(invite);
            }
            case FRIEND_INVITE_DELETED_EVT -> {
                Invite invite = new Invite(null, null);
                int reason = INVITE_ACCEPT;
                int tag;
                while ((tag = in.readTag()) != 0) {
                    switch (tag >>> 3) {
                        case 1 -> invite = readInvite(in.readByteArray());
                        case 2 -> reason = in.readInt32();
                        default -> in.skipField(tag);
                    }
                }
                yield new InviteDeleted(invite, reason);
            }
            case USER_FRIEND_CREATED_EVT -> {
                long userId = 0;
                User friend = null;
                int tag;
                while ((tag = in.readTag()) != 0) {
                    switch (tag >>> 3) {
                        case 1 -> userId = in.readInt64();
                        case 2 -> friend = readFriend(in.readByteArray());
                        default -> in.skipField(tag);
                    }
                }
                yield new FriendCreated(userId, friend);
            }
            case USER_FRIEND_DELETED_EVT -> {
                long userId = 0;
                long friendUserId = 0;
                int tag;
                while ((tag = in.readTag()) != 0) {
                    switch (tag >>> 3) {
                        case 1 -> userId = in.readInt64();
                        case 2 -> friendUserId = in.readInt64();
                        default -> in.skipField(tag);
                    }
                }
                yield new FriendDeleted(userId, friendUserId);
            }
            case USER_PRESENCE_UPDATED_EVT -> {
                long userId = 0;
                int presence = PRESENCE_ONLINE;
                int tag;
                while ((tag = in.readTag()) != 0) {
                    switch (tag >>> 3) {
                        case 1 -> userId = in.readInt64();
                        case 2 -> presence = in.readInt32();
                        default -> in.skipField(tag);
                    }
                }
                yield new PresenceUpdated(userId, presence);
            }
            case USER_ENDPOINT_PROPERTIES_UPDATED_EVT -> {
                long userId = 0;
                List<EndpointProperties> properties = new ArrayList<>();
                int tag;
                while ((tag = in.readTag()) != 0) {
                    switch (tag >>> 3) {
                        case 1 -> userId = in.readInt64();
                        case 2 -> properties.add(readEndpoint(in.readByteArray()));
                        default -> in.skipField(tag);
                    }
                }
                yield new EndpointsUpdated(userId, List.copyOf(properties));
            }
            default -> new Unknown(payload.id());
        };
    }

    // ---------- shared decoders ----------

    private static User readUser(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        long userId = 0;
        String name = "";
        String tag = "";
        int t;
        while ((t = in.readTag()) != 0) {
            switch (t >>> 3) {
                case 1 -> userId = in.readInt64();
                case 2 -> name = in.readString();
                case 3 -> tag = in.readString();
                default -> in.skipField(t);
            }
        }
        return new User(userId, name, tag);
    }

    /** {@code Friend { User user = 1; Group group = 2; string note = 3 }}: only the user matters. */
    private static User readFriend(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        User user = null;
        int tag;
        while ((tag = in.readTag()) != 0) {
            if (tag >>> 3 == 1) {
                user = readUser(in.readByteArray());
            } else {
                in.skipField(tag);
            }
        }
        return user;
    }

    private static Invite readInvite(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        User inviter = null;
        User recipient = null;
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (tag >>> 3) {
                case 1 -> inviter = readUser(in.readByteArray());
                case 2 -> recipient = readUser(in.readByteArray());
                default -> in.skipField(tag);
            }
        }
        return new Invite(inviter, recipient);
    }

    private static EndpointProperties readEndpoint(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int applicationId = 0;
        List<String> activities = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (tag >>> 3) {
                case 1 -> applicationId = in.readInt32();
                case 2 -> activities.add(in.readString());
                case 3 -> readMapEntry(in.readByteArray(), metadata);
                default -> in.skipField(tag);
            }
        }
        return new EndpointProperties(applicationId, List.copyOf(activities), Map.copyOf(metadata));
    }

    private static void readMapEntry(byte[] bytes, Map<String, String> into) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        String key = "";
        String value = "";
        int tag;
        while ((tag = in.readTag()) != 0) {
            switch (tag >>> 3) {
                case 1 -> key = in.readString();
                case 2 -> value = in.readString();
                default -> in.skipField(tag);
            }
        }
        into.put(key, value);
    }

    // ---------- encoding helpers ----------

    @FunctionalInterface
    private interface Writer {
        void accept(CodedOutputStream out) throws IOException;
    }

    private static Payload build(int id, Writer writer) {
        return new Payload(id, bytes(writer));
    }

    private static byte[] bytes(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            writer.accept(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            // writing to a byte array cannot fail
            throw new IllegalStateException(e);
        }
    }
}
