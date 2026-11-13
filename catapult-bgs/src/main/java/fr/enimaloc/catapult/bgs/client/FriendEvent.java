package fr.enimaloc.catapult.bgs.client;

/**
 * A change of the bot's friends list, from a FriendsListener push or from the resync diff of a new session. Each
 * event is emitted once: an element already known (or already gone) produces nothing.
 */
public sealed interface FriendEvent {

    /**
     * Reason of an {@link InvitationSentRemoved} found by the resync diff: the invitation vanished while offline and
     * its outcome is unknown. Accepted offline, it shows up as a {@link FriendAdded} in the same resync; declined or
     * revoked, it does not.
     */
    int REASON_GONE_WHILE_OFFLINE = -1;

    record FriendAdded(long accountId, String battleTag) implements FriendEvent {
    }

    record FriendRemoved(long accountId, String battleTag) implements FriendEvent {
    }

    record InvitationReceived(long invitationId, long accountId, String battleTag) implements FriendEvent {
    }

    /** A received invitation is gone (accepted, declined, revoked or expired); {@code reason} is the server's code. */
    record InvitationRemoved(long invitationId, long accountId, String battleTag, int reason) implements FriendEvent {
    }

    /** {@code targetName} is the BattleTag the invitation was sent to: the server gives no account id for it. */
    record InvitationSent(long invitationId, String targetName) implements FriendEvent {
    }

    /**
     * A sent invitation is gone. From a push, {@code reason} is the server's: 0 means accepted (a {@link FriendAdded}
     * follows), any other value means declined or revoked. From the resync diff it is
     * {@link FriendEvent#REASON_GONE_WHILE_OFFLINE}.
     */
    record InvitationSentRemoved(long invitationId, String targetName, int reason) implements FriendEvent {
    }
}
