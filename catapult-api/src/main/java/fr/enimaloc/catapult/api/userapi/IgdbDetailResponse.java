package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record IgdbDetailResponse(@JsonIgnore String baseUrl, String slug, String url, String summary,
                                 String storyline, List<String> platforms, Double rating,
                                 Double aggregatedRating, Double totalRating, Integer totalRatingCount,
                                 Instant releaseDate, Map<String, Object> websites, List<IgdbRef> dlcs,
                                 List<IgdbRef> similarGames, List<String> genres, List<String> gameModes,
                                 List<String> themes, List<String> playerPerspectives, List<String> keywords,
                                 List<String> franchiseNames, String coverUrl, List<String> screenshotUrls,
                                 List<String> videoUrls) {
    private static final String CATEGORY_NULL_KEY = "category_null";

    // Points at this same API's own /igdb/{id} detail route — dlcs and similarGames are other
    // IGDB games, already fully describable by that route, not a foreign platform.
    public record IgdbRef(String igdbId, String name, @JsonIgnore String baseUrl) {
        @JsonGetter
        public String more() {
            return baseUrl + ApiV2.PATH + "/igdb/" + igdbId;
        }
    }

    static List<IgdbRef> zipRefs(List<String> ids, List<String> names, String baseUrl) {
        List<IgdbRef> refs = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size() && i < names.size(); i++) {
            refs.add(new IgdbRef(ids.get(i), names.get(i), baseUrl));
        }
        return refs;
    }

    // IGDB deprecated Website#category (it's now unset/0 on virtually every entry, i.e.
    // "category_null"), so the actual site has to be sniffed from the URL's host instead.
    private static final List<Map.Entry<String, String>> CATEGORY_NULL_DOMAINS = List.of(
            Map.entry("xbox.com", "xbox"),
            Map.entry("wikipedia.org", "wikipedia"),
            Map.entry("wikia.com", "wikia"),
            Map.entry("fandom.com", "wikia"),
            Map.entry("epicgames.com", "epic_games"),
            Map.entry("gog.com", "gog"),
            Map.entry("facebook.com", "facebook"),
            Map.entry("twitter.com", "twitter"),
            Map.entry("x.com", "twitter"),
            Map.entry("twitch.tv", "twitch"),
            Map.entry("instagram.com", "instagram"),
            Map.entry("youtube.com", "youtube"),
            Map.entry("youtu.be", "youtube"),
            Map.entry("reddit.com", "reddit"),
            Map.entry("itch.io", "itch"),
            Map.entry("discord.gg", "discord"),
            Map.entry("discord.com", "discord"),
            Map.entry("bsky.app", "bluesky"),
            Map.entry("apps.apple.com", "app_store"),
            Map.entry("play.google.com", "google_play"),
            Map.entry("steampowered.com", "steam"),
            Map.entry("microsoft.com", "microsoft"));

    // Platform keys for which this API also exposes a /more detail route, and the path to
    // build it from the raw id — only reachable for external_games-sourced entries, since
    // those carry a clean id; a category_null entry that happens to resolve to the same key
    // only has a URL, with no id we can safely parse back out of it, so it stays a plain link.
    private static final Map<String, String> DETAIL_ROUTE_PATH_PREFIX = Map.of(
            "steam", "/steam/", "microsoft", "/xbox/");

    public record WebsiteLink(String url, @JsonIgnore String baseUrl, @JsonIgnore String path) {
        @JsonGetter
        public String more() {
            return baseUrl + ApiV2.PATH + path;
        }
    }

    public IgdbDetailResponse {
        // Build a fresh map rather than mutating the incoming one in place: it's the same Map
        // instance IgdbGameDetails#getWebsites() returns, so an in-place put() here would leak
        // back into the cached entity. A plain forEach + put on the same map would also throw
        // ConcurrentModificationException the moment a new key (not already present) is added.
        Map<String, Object> normalized = new LinkedHashMap<>();
        websites.forEach((key, value) -> {
            // external_games-sourced entries (see IgdbService#init — "Steam", "Microsoft",
            // "Twitch") have a reliable key but a raw id as the value, not a URL, unlike the
            // website-sourced entries (resolved below via resolveCategoryNullKey) which are
            // already full URLs — the id→URL(+more) transform must run on the *original* key,
            // before resolution, or a category_null entry that resolves to e.g. "steam" would
            // get its already-complete URL wrongly re-prefixed.
            boolean fromCategoryNull = key.equals(CATEGORY_NULL_KEY);
            String resolvedKey = fromCategoryNull ? resolveCategoryNullKey((String) value) : key;
            String id = (String) value;
            Object resolvedValue = fromCategoryNull ? value : switch (key) {
                case "steam" -> new WebsiteLink("https://store.steampowered.com/app/" + id, baseUrl,
                        DETAIL_ROUTE_PATH_PREFIX.get("steam") + id);
                case "microsoft" -> new WebsiteLink("https://www.microsoft.com/store/apps/" + id, baseUrl,
                        DETAIL_ROUTE_PATH_PREFIX.get("microsoft") + id);
                case "twitch" -> value; // TODO: this is a Twitch game id, not a slug — need the slug to build a directory link
                default -> value;
            };
            normalized.put(resolvedKey, resolvedValue);
        });
        websites = normalized;
    }

    private static String resolveCategoryNullKey(String url) {
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return CATEGORY_NULL_KEY;
        }
        if (host == null) {
            return CATEGORY_NULL_KEY;
        }
        for (Map.Entry<String, String> domain : CATEGORY_NULL_DOMAINS) {
            if (host.equals(domain.getKey()) || host.endsWith("." + domain.getKey())) {
                return domain.getValue();
            }
        }
        return CATEGORY_NULL_KEY;
    }
}
