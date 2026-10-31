package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/** A member group as catapult-api lists it for the admin Groups tab; {@code description} may be null. */
public record GroupSummaryDto(UUID id, String key, String name, String description, int memberCount) {}
