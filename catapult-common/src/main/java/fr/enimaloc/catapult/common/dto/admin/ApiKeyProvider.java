package fr.enimaloc.catapult.common.dto.admin;

/**
 * The providers whose shared API keys an admin manages: the path segment of catapult-api's
 * {@code /api/internal/admin/{apiPath}} routes, and the {@code steam}, {@code dtdd}, {@code steamgriddb}, {@code itad}, {@code opencritic}, {@code retroachievements}, {@code riot}, {@code faceit}, {@code hypixel} or Supercell's {@code clashofclans}, {@code clashroyale} or {@code brawlstars} one catapult-web uses.
 */
public enum ApiKeyProvider {
    STEAM("steam-keys", "steam"),
    DTDD("dtdd-keys", "dtdd"),
    STEAMGRIDDB("steamgriddb-keys", "steamgriddb"),
    ITAD("itad-keys", "itad"),
    OPENCRITIC("opencritic-keys", "opencritic"),
    RETROACHIEVEMENTS("retroachievements-keys", "retroachievements"),
    RIOT("riot-keys", "riot"),
    FACEIT("faceit-keys", "faceit"),
    HYPIXEL("hypixel-keys", "hypixel"),
    CLASHOFCLANS("supercell-keys/clash-of-clans/keys", "clashofclans"),
    CLASHROYALE("supercell-keys/clash-royale/keys", "clashroyale"),
    BRAWLSTARS("supercell-keys/brawl-stars/keys", "brawlstars");

    private final String apiPath;
    private final String pathName;

    ApiKeyProvider(String apiPath, String pathName) {
        this.apiPath = apiPath;
        this.pathName = pathName;
    }

    public String apiPath() {
        return apiPath;
    }

    public String pathName() {
        return pathName;
    }

    /** The provider named {@code steam}, {@code dtdd}, {@code steamgriddb}, {@code itad}, {@code opencritic}, {@code retroachievements}, {@code riot}, {@code faceit}, {@code hypixel}, {@code clashofclans}, {@code clashroyale} or {@code brawlstars}; null else. */
    public static ApiKeyProvider of(String pathName) {
        for (ApiKeyProvider provider : values()) {
            if (provider.pathName.equals(pathName)) return provider;
        }
        return null;
    }
}
