package fr.enimaloc.catapult.common.dto.channel;

import java.util.Set;

public record UpdateBindingRequest(String twitchGameId, String twitchGameName, Set<String> ccls) {}
