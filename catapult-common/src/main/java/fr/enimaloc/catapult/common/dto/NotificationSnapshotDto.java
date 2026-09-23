package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record NotificationSnapshotDto(List<NotificationDto> items, long unreadCount) {}
