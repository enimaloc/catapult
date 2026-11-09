package fr.enimaloc.catapult.twitchat;

import java.util.UUID;

/**
 * Asks catapult-api to hand a member's pending Catapult notifications to their Twitchat: they come
 * back through the Twitchat dispatch Redis path, to the streams {@link TwitchatDispatchHub} holds open.
 * Each call answers catapult-api's status (502 when unreachable): a flush is best effort.
 */
public interface NotificationFlusher {

    /** The owner's Twitchat page is open: flush with their session's {@code jwt}. */
    int flushPage(String jwt);

    /** The owner's OBS widget is open: flush with its {@code token}. */
    int flushWidget(UUID token);
}
