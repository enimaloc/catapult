package fr.enimaloc.catapult.common.dto.account;

import java.time.Instant;

/** Whether the member has a personal API token, and since when (never the token itself). */
public record ApiTokenStatusDto(boolean exists, Instant createdAt) {}
