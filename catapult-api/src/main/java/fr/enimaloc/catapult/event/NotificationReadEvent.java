package fr.enimaloc.catapult.event;

import java.util.UUID;

public record NotificationReadEvent(UUID userId, UUID notificationId, long newUnreadCount) {}
