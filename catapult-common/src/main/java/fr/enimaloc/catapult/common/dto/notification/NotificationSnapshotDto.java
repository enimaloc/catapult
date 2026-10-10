package fr.enimaloc.catapult.common.dto.notification;

import java.util.List;

public record NotificationSnapshotDto(List<NotificationDto> items, long unreadCount) {}
