package fr.enimaloc.catapult.common.dto.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String title,
        String bodyHtml,
        Severity severity,
        String ctaUrl,
        String ctaLabel,
        Instant expiresAt,
        Instant createdAt,
        boolean read
) {}
