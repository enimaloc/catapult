package fr.enimaloc.catapult.common.dto.admin;

/** What catapult-api answers a notification sent to several members: how many it reached ({@code sent}). */
public record NotificationSentDto(Integer sent) {}
