package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

/**
 * Snapshot du jeu en cours pour un user, hydraté par GameContextService et lu par PlaceholderResolver.
 * Tous les champs sauf `detected`/`name` peuvent être null.
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
    fr.enimaloc.catapult.getter.DtddApiClient.DtddTopics dtddTopics,
    String ageRating
) {
    public static GameContext empty() {
        return new GameContext(null, null, null, null, null, Collections.emptyMap(), null, null, null, null);
    }

    public static String storeKey(GameBinding.SourceType sourceType) {
        if (sourceType == null) return null;
        return sourceType.name().toLowerCase();
    }
}
