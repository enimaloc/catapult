package fr.enimaloc.catapult.common.dto.notification;

/** Markdown body an admin wants to see rendered before sending. */
public record NotificationPreviewRequest(String body) {}
