package fr.enimaloc.catapult.common.dto;

import java.util.Set;

public record CclSettingsRequest(boolean cclEnabled, Set<String> blockedCcls) {}
