package fr.enimaloc.catapult.common.dto.account;

import java.time.Instant;

/** A personal API token just generated: the only time it is shown, only its hash is kept. */
public record ApiTokenCreatedDto(String token, Instant createdAt) {}
