package fr.enimaloc.catapult.common.dto;

import java.util.Set;

public record NoGameSettingsRequest(
        String twitchGameId, String twitchGameName, Set<String> ccls,
        boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {}
