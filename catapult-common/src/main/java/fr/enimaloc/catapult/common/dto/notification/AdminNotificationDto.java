package fr.enimaloc.catapult.common.dto.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin view of a notification: the stored text, its rendered body, and for a targeted one
 * the member it was sent to. Replaces exposing the JPA entity on the admin list.
 */
public record AdminNotificationDto(UUID id, String title, String body, String bodyHtml,
        Severity severity, String ctaUrl, String ctaLabel, Instant expiresAt, Instant createdAt,
        String audience, UUID targetUserId, String targetLogin) {}
