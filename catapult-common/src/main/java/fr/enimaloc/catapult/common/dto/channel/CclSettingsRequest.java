package fr.enimaloc.catapult.common.dto.channel;

import java.util.Set;

public record CclSettingsRequest(boolean cclEnabled, Set<String> blockedCcls) {}
