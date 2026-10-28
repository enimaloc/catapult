package fr.enimaloc.catapult.event.notification;

import java.util.UUID;

/**
 * A notification event for one account's streams: {@code name} is the SSE event name, {@code data}
 * what catapult-api published (a NotificationDto, or a map with the notification id and unread count).
 */
public record NotificationStreamEvent(UUID accountId, String name, Object data) {
    public static final String CREATED = "NotificationCreated";
    public static final String READ = "NotificationRead";
    public static final String ALL_READ = "NotificationsAllRead";
    public static final String DELETED = "NotificationDeleted";
}
