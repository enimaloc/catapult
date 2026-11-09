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
        /**
         * {@link #CHAT_COMMANDS} for one notification per streamer with an enabled chat command,
         * {@link #EXPERIMENT} for one shared notification to an experiment's participants; null otherwise.
         */
        String audience,
        /** The experiment whose participants are told; required for {@link #EXPERIMENT}, ignored otherwise. */
        UUID experimentId,
        /** Narrows {@link #EXPERIMENT} to one variant of {@link #experimentId}; null for every variant. */
        UUID variantId
) {
    /** Each streamer with an enabled chat command, told alone (their channel may be in the CTA as {channel}). */
    public static final String CHAT_COMMANDS = "CHAT_COMMANDS";

    /** The active members assigned to an experiment (or to one of its variants), told by one shared notification. */
    public static final String EXPERIMENT = "EXPERIMENT";

    public AdminNotificationCreateBody(String title, String body, Severity severity, String ctaUrl, String ctaLabel,
                                       Instant expiresAt, UUID targetUserId, String audience) {
        this(title, body, severity, ctaUrl, ctaLabel, expiresAt, targetUserId, audience, null, null);
    }

    public AdminNotificationCreateBody(String title, String body, Severity severity, String ctaUrl, String ctaLabel,
                                       Instant expiresAt, UUID targetUserId) {
        this(title, body, severity, ctaUrl, ctaLabel, expiresAt, targetUserId, null, null, null);
    }
}
