package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;

import java.util.UUID;

/**
 * A change of a bot's friends or invites, republished off the chat connection by {@link AnkamaSessionManager}, in
 * the order the chat reported it. After each connection, the pending invites and the friends are replayed as
 * {@link ChatMessages.InviteCreated} and {@link ChatMessages.FriendCreated}, with {@code replayed} set: a replayed
 * friend may be a friendship left over from a link deleted while the bot was away.
 */
public record AnkamaFriendEventReceived(UUID serviceAccountId, ChatMessages.Event event, boolean replayed) {

    /** A live event, as the chat pushed it. */
    public AnkamaFriendEventReceived(UUID serviceAccountId, ChatMessages.Event event) {
        this(serviceAccountId, event, false);
    }
}
