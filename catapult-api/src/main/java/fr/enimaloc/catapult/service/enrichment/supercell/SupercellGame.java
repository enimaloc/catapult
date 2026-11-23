package fr.enimaloc.catapult.service.enrichment.supercell;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;
import java.util.Optional;

/**
 * The Supercell games the enrichment knows. Each has its own API, host and developer portal: a key of one game's
 * portal is refused by the others (its JWT carries the game's {@code scope}).
 */
public enum SupercellGame {
    @JsonProperty("clash-of-clans") CLASH_OF_CLANS("clash-of-clans", "https://api.clashofclans.com", "clash",
            "Clash of Clans"),
    @JsonProperty("clash-royale") CLASH_ROYALE("clash-royale", "https://api.clashroyale.com", "royale",
            "Clash Royale"),
    @JsonProperty("brawl-stars") BRAWL_STARS("brawl-stars", "https://api.brawlstars.com", "brawlstars",
            "Brawl Stars");

    private final String key;
    private final String host;
    private final String scope;
    private final String title;

    SupercellGame(String key, String host, String scope, String title) {
        this.key = key;
        this.host = host;
        this.scope = scope;
        this.title = title;
    }

    /** {@code clash-of-clans}, {@code clash-royale} or {@code brawl-stars}: the route segment and the stored name. */
    public String key() {
        return key;
    }

    /** The API's scheme and host, without the {@code /v1}. */
    public String host() {
        return host;
    }

    /** The scope a key of this game's developer portal carries in its JWT ({@code clash}, {@code royale}…). */
    public String scope() {
        return scope;
    }

    /** The game's name as Supercell and Twitch spell it. */
    public String title() {
        return title;
    }

    /** The game named {@code key} (any case, surrounding blanks ignored); empty for none. */
    public static Optional<SupercellGame> parse(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.strip().toLowerCase(Locale.ROOT);
        for (SupercellGame game : values()) {
            if (game.key.equals(k)) {
                return Optional.of(game);
            }
        }
        return Optional.empty();
    }

    /** The game whose name is {@code title} (any case), as a detected category is named; empty for none. */
    public static Optional<SupercellGame> byTitle(String title) {
        if (title == null) {
            return Optional.empty();
        }
        String t = title.strip();
        for (SupercellGame game : values()) {
            if (game.title.equalsIgnoreCase(t)) {
                return Optional.of(game);
            }
        }
        return Optional.empty();
    }
}
