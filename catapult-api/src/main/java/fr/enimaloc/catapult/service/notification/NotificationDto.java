package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.Notification;
import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String title,
        String bodyHtml,
        Notification.Severity severity,
        String ctaUrl,
        String ctaLabel,
        Instant expiresAt,
        Instant createdAt,
        boolean read
) {}
