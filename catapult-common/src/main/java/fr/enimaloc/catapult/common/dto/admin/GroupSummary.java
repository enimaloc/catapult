package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

public record GroupSummary(UUID id, String key, String name, String description, int memberCount) {}
