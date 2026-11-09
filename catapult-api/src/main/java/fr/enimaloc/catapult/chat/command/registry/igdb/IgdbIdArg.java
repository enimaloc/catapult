package fr.enimaloc.catapult.chat.command.registry.igdb;

import java.util.Map;

/**
 * Resolves the IGDB id a {@code igdb#get*} sub-data function needs from its first argument,
 * which the streamer may plug in either as the {@code igdb#getGame} result object (a {@code Map}
 * with an {@code "id"} key) or as a bare igdb id — so {@code igdb#getExternalPlatforms(game)} and
 * {@code igdb#getExternalPlatforms(game.id)} both work.
 */
final class IgdbIdArg {

    private IgdbIdArg() {
    }

    static String resolve(Object arg) {
        if (arg instanceof Map<?, ?> map && map.get("id") != null) {
            return String.valueOf(map.get("id"));
        }
        return String.valueOf(arg);
    }
}
