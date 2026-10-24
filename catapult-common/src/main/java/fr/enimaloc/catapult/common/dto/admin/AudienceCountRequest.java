package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/** Who a notification would reach, asked before sending: {@code audience} is {@link AdminNotificationCreateBody#EXPERIMENT}. */
public record AudienceCountRequest(String audience, UUID experimentId, UUID variantId) {}
