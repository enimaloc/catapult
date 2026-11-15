package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient.Summary;

import java.util.UUID;

/**
 * The friends, received and sent requests of a bot, as Epic's friends service listed them; published by
 * {@link EpicSessionManager} after each connection, on every {@code friends.v1.*} notification and periodically.
 * Always a full snapshot: the notifications' payloads are not known (spec §11.1), they only trigger a new listing.
 */
public record EpicFriendEventReceived(UUID serviceAccountId, Summary summary) {
}
