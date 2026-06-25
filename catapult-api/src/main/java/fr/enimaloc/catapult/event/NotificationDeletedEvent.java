package fr.enimaloc.catapult.event;

import java.util.UUID;

public record NotificationDeletedEvent(UUID userId, UUID notificationId) {}
