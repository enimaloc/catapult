package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot of a user's current game, hydrated by GameContextService and read by PlaceholderResolver.
 * Every field except `detected`/`name` may be null.
 */
public record GameContext(
    DetectedGame detected,
    String igdbId,
    String name,
    String summary,
    LocalDate releaseDate,
    Map<String, String> stores,
    String activeStoreUrl,
    String igdbSlug,
    Set<String> activeTws,
    Map<String, String> twLabels,
    String ageRating,
    Double rating,
    Double criticRating,
    List<String> platforms,
    List<String> dlcNames,
    List<String> similarGameNames
) {
    public static GameContext empty() {
        return new GameContext(null, null, null, null, null, Collections.emptyMap(), null, null,
            Collections.emptySet(), Collections.emptyMap(), null,
            null, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public static String storeKey(GameBinding.SourceType sourceType) {
        if (sourceType == null) return null;
        return sourceType.name().toLowerCase();
    }
}
