package fr.enimaloc.catapult.service.enrichment.twitch;

import java.util.List;

/** The most viewed recent clips of a category. */
public record TwitchClips(List<Clip> top) {

    public record Clip(String title, String channel, int views, double duration, String url, String thumbnailUrl) {
    }
}
