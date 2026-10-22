package fr.enimaloc.catapult.common.dto.notification;

/** How many pending notifications a flush handed to Twitchat. */
public record NotificationFlushResultDto(int delivered) {}
