package fr.enimaloc.catapult.common.dto;

import java.util.Set;

public record IncompleteFallbackRequest(String twitchGameId, String twitchGameName, Set<String> ccls) {}
