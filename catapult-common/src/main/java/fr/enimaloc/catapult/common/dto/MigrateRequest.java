package fr.enimaloc.catapult.common.dto;

import java.util.UUID;

public record MigrateRequest(UUID targetId, boolean migrateSettings, boolean migrateGetters, boolean migrateBindings) {}
