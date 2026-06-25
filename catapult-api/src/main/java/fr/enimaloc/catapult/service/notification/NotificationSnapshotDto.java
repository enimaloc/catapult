package fr.enimaloc.catapult.service.notification;

import java.util.List;

public record NotificationSnapshotDto(List<NotificationDto> items, long unreadCount) {}
