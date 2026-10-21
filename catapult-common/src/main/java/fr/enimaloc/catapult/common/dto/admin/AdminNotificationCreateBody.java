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
        UUID targetUserId,
        /** {@link #CHAT_COMMANDS} for one notification per streamer with an enabled chat command; null otherwise. */
        String audience
) {
    /** Each streamer with an enabled chat command, told alone (their channel may be in the CTA as {channel}). */
    public static final String CHAT_COMMANDS = "CHAT_COMMANDS";

    public AdminNotificationCreateBody(String title, String body, Severity severity, String ctaUrl, String ctaLabel,
                                       Instant expiresAt, UUID targetUserId) {
        this(title, body, severity, ctaUrl, ctaLabel, expiresAt, targetUserId, null);
    }
}
