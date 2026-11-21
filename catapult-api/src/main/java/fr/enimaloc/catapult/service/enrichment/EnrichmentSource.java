package fr.enimaloc.catapult.service.enrichment;

/** The external sources a game can be enriched from. */
public enum EnrichmentSource {
    HLTB("hltb"),
    PROTONDB("protondb"),
    STEAMGRIDDB("steamgriddb"),
    STEAMDECK("steamdeck"),
    PCGAMINGWIKI("pcgamingwiki"),
    TRAILER("trailer"),
    PRICES("prices"),
    OPENCRITIC("opencritic"),
    TWITCH_CATEGORY("twitch-category"),
    TWITCH_LIVES("twitch-lives"),
    TWITCH_CLIPS("twitch-clips"),
    SPEEDRUN_GAME("speedrun-game"),
    SPEEDRUN_RECORDS("speedrun-records"),
    SPEEDRUN_USER("speedrun-user"),
    SPEEDRUN_PBS("speedrun-pbs"),
    SPEEDRUN_PENDING("speedrun-pending");

    private final String key;

    EnrichmentSource(String key) {
        this.key = key;
    }

    /** Stable identifier used in the cache table and in the property names. */
    public String key() {
        return key;
    }
}
