package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import java.util.List;

/**
 * SteamGridDB artwork for a game.
 *
 * @param gameId SteamGridDB game id
 * @param name   the game's name on SteamGridDB
 * @param url    the game's SteamGridDB page
 * @param grids  capsule/grid images
 * @param heroes hero banners
 * @param logos  logos
 * @param icons  icons
 */
public record SteamGridDbAssets(long gameId, String name, String url, List<Asset> grids, List<Asset> heroes,
                                List<Asset> logos, List<Asset> icons) {

    /** One image. */
    public record Asset(String url, String thumb, int width, int height, String style) {
    }

    /** The first grid, or null when there is none. */
    public Asset bestGrid() {
        return first(grids);
    }

    /** The first hero, or null when there is none. */
    public Asset bestHero() {
        return first(heroes);
    }

    /** The first logo, or null when there is none. */
    public Asset bestLogo() {
        return first(logos);
    }

    /** The first icon, or null when there is none. */
    public Asset bestIcon() {
        return first(icons);
    }

    private static Asset first(List<Asset> assets) {
        return assets == null || assets.isEmpty() ? null : assets.getFirst();
    }
}
