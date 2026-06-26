package fr.enimaloc.catapult.client;

import java.util.List;

/**
 * Mirror of {@code fr.enimaloc.catapult.service.notification.NotificationSnapshotDto} in catapult-api.
 * Field names and order must stay identical so Jackson deserialisation matches the wire format.
 */
public record NotificationSnapshotDto(List<NotificationDto> items, long unreadCount) {}
