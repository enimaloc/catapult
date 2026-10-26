package fr.enimaloc.catapult.service.link;

/**
 * The one place that builds the URL of a third-party page. Every response and detail
 * object takes its external links from here, so a format is fixed once.
 */
public final class ExternalLinks {

    private ExternalLinks() {}

    /**
     * The IGDB page of a game, {@code https://www.igdb.com/games/<slug>} (the path IGDB itself serves).
     *
     * @return the page, or null when the game has no slug
     */
    public static String igdbGame(String slug) {
        return slug == null || slug.isBlank() ? null : "https://www.igdb.com/games/" + slug;
    }

    /** The Steam store page of an app, {@code https://store.steampowered.com/app/<appId>}. */
    public static String steamApp(String appId) {
        return "https://store.steampowered.com/app/" + appId;
    }

    /** The Microsoft Store page of a product, {@code https://www.microsoft.com/store/apps/<productId>}. */
    public static String microsoftStoreProduct(String productId) {
        return "https://www.microsoft.com/store/apps/" + productId;
    }

    /** The DoesTheDogDie page of a media, {@code https://www.doesthedogdie.com/media/<id>}. */
    public static String dtddMedia(long dtddId) {
        return "https://www.doesthedogdie.com/media/" + dtddId;
    }

    /** The YouTube watch page of a video, {@code https://www.youtube.com/watch?v=<videoId>}. */
    public static String youtubeVideo(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
