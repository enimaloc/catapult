package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.bgs.client.FriendEvent;

import java.util.UUID;

/**
 * A change of a bot's friends list, republished off the BGS session loop by {@link BattleNetSessionManager}, in the
 * order the session reported it.
 */
public record BattleNetFriendEventReceived(UUID serviceAccountId, FriendEvent event) {
}
