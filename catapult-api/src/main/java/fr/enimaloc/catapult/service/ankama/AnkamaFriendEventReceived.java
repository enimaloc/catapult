package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;

import java.util.UUID;

/**
 * A change of a bot's friends or invites, republished off the chat connection by {@link AnkamaSessionManager}, in
 * the order the chat reported it. After each connection, the pending invites and the friends are replayed as
 * {@link ChatMessages.InviteCreated} and {@link ChatMessages.FriendCreated}.
 */
public record AnkamaFriendEventReceived(UUID serviceAccountId, ChatMessages.Event event) {
}
