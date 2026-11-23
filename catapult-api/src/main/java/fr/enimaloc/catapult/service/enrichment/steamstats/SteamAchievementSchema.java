package fr.enimaloc.catapult.service.enrichment.steamstats;

import java.util.List;

/** The achievements an app defines, in the language asked ({@code GetSchemaForGame}). */
public record SteamAchievementSchema(List<Definition> achievements) {

    /** One achievement: its API name, texts and the two icons (unlocked, locked). */
    public record Definition(String apiName, String displayName, String description, String icon,
                             String iconGray, boolean hidden) {
    }
}
