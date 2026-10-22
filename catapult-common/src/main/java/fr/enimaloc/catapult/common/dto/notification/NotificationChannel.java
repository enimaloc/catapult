package fr.enimaloc.catapult.common.dto.notification;

/** Where a member may also receive Catapult's notifications, besides the site (always). */
public enum NotificationChannel {
    /** As a Catapult event in their Twitchat, when their Twitchat page or OBS widget is connected (else at the next connection). */
    TWITCHAT,
    /** Posted by Catapult's bot in their own Twitch chat at their next stream start. */
    STREAM_START_CHAT
}
