package fr.enimaloc.catapult.service.ankama;

import com.google.protobuf.CodedOutputStream;
import fr.enimaloc.catapult.service.ankama.chat.AnkamaChatAuthException;
import fr.enimaloc.catapult.service.ankama.chat.AnkamaChatClient;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames;
import fr.enimaloc.catapult.service.ankama.chat.ChatListener;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An in-memory {@link AnkamaChatClient}: no socket. Answers the friend and invite lists with what the test set,
 * lets the test push events and drop the connection.
 */
class FakeChatClient extends AnkamaChatClient {

    String token;
    String deviceId;
    ChatListener listener;
    boolean closed;
    /** Thrown by {@link #connect}, when set. */
    IOException connectFailure;
    List<ChatMessages.User> friends = List.of();
    List<ChatMessages.Invite> invites = List.of();
    final List<ChatFrames.Payload> requests = new ArrayList<>();

    FakeChatClient() {
        super(null, "chat.test", 6337, Duration.ofSeconds(20), null);
    }

    static FakeChatClient refusing() {
        FakeChatClient client = new FakeChatClient();
        client.connectFailure = new AnkamaChatAuthException("The chat server refused the chat token");
        return client;
    }

    @Override
    public void connect(String chatToken, String deviceId, ChatListener listener) throws IOException {
        this.token = chatToken;
        this.deviceId = deviceId;
        if (connectFailure != null) {
            throw connectFailure;
        }
        this.listener = listener;
    }

    @Override
    public CompletableFuture<ChatFrames.Response> request(ChatFrames.Payload payload) {
        requests.add(payload);
        if (closed) {
            return CompletableFuture.failedFuture(new IOException("The chat connection is closed"));
        }
        return switch (payload.id()) {
            case ChatMessages.LIST_USER_FRIENDS_CMD -> CompletableFuture.completedFuture(
                    new ChatFrames.Response(1, 1, new ChatFrames.Payload(ChatMessages.FRIEND_LIST, friendList())));
            case ChatMessages.LIST_USER_FRIEND_INVITES_CMD -> CompletableFuture.completedFuture(
                    new ChatFrames.Response(2, 1,
                            new ChatFrames.Payload(ChatMessages.FRIEND_INVITE_LIST, inviteList())));
            default -> CompletableFuture.completedFuture(new ChatFrames.Response(3, 1, null));
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (listener != null) {
            listener.onClosed(null);
        }
    }

    /** The server ends the connection. */
    void drop() {
        closed = true;
        listener.onClosed(new EOFException("The chat server closed the connection"));
    }

    void push(ChatMessages.Event event) {
        listener.onEvent(event);
    }

    private byte[] friendList() {
        return bytes(o -> {
            for (ChatMessages.User friend : friends) {
                o.writeByteArray(1, bytes(f -> f.writeByteArray(1, user(friend))));
            }
        });
    }

    private byte[] inviteList() {
        return bytes(o -> {
            for (ChatMessages.Invite invite : invites) {
                o.writeByteArray(1, bytes(i -> {
                    i.writeByteArray(1, user(invite.inviter()));
                    i.writeByteArray(2, user(invite.recipient()));
                }));
            }
        });
    }

    private static byte[] user(ChatMessages.User user) {
        return bytes(o -> {
            o.writeInt64(1, user.userId());
            o.writeString(2, user.name());
            o.writeString(3, user.tag());
        });
    }

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
}
