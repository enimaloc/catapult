package fr.enimaloc.catapult.common.dto.admin;

import fr.enimaloc.catapult.common.dto.notification.Severity;

import java.time.Instant;
import java.util.UUID;

public record AdminNotificationCreateBody(
        String title,
        String body,
        Severity severity,
        String ctaUrl,
        String ctaLabel,
        Instant expiresAt,
        UUID targetUserId
) {}
