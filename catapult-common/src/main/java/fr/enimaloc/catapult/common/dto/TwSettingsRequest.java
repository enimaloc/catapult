package fr.enimaloc.catapult.common.dto;

import java.util.Set;

public record TwSettingsRequest(boolean enabled, Set<String> blockedTws) {}
