package fr.enimaloc.catapult.common.dto.channel;

import java.util.Set;

public record IncompleteFallbackRequest(String twitchGameId, String twitchGameName, Set<String> ccls) {}
