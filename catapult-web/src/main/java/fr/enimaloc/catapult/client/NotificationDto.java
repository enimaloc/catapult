package fr.enimaloc.catapult.client;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirror of {@code fr.enimaloc.catapult.service.notification.NotificationDto} in catapult-api.
 * Field names and order must stay identical so Jackson deserialisation matches the wire format.
 */
public record NotificationDto(
        UUID id,
        String title,
        String bodyHtml,
        String severity,
        String ctaUrl,
        String ctaLabel,
        Instant expiresAt,
        Instant createdAt,
        boolean read
) {}
