package fr.enimaloc.catapult.domain.twitchat;

public enum TwitchatNotificationEventType {
    CATEGORY_CHANGED_BY_CATAPULT,
    CATEGORY_CHANGED_MANUALLY,
    STREAM_STARTED,
    BOT_ENABLED,
    BOT_DISABLED,
    NO_GAME_DETECTED,
    /** A Catapult notification, for members who opted in to receiving them in Twitchat. */
    NOTIFICATION,
    /** Another Catapult member raided this one: what the raider streamed during their session. */
    RAIDED_BY_MEMBER
}
