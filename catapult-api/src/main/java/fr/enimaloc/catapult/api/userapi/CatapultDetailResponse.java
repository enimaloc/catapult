package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.GameBinding;

import java.time.Instant;
import java.util.Set;

public record CatapultDetailResponse(Instant createdAt, Instant updatedAt, GameBinding.SourceType sourceType,
                                     String sourceId, String sourceName, String twitchGameId,
                                     String twitchGameName, boolean ignored, boolean cclEnabled,
                                     Set<String> ccls, boolean twEnabled, boolean twOverride, Set<String> tws,
                                     Set<String> twLabels) {}
