package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.UUID;

public record ModuleOverrideDto(
        String key,
        String value,
        boolean secret,
        Instant updatedAt,
        UUID updatedBy) {}
