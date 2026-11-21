package fr.enimaloc.catapult.service.enrichment.twitch;

import java.util.List;

/**
 * The live streams of a category: how many, their viewers, and the biggest ones. {@code approximate} when the
 * category had more lives than the pages read.
 */
public record TwitchLives(int liveCount, long viewers, boolean approximate, List<Stream> top) {

    public record Stream(String channel, String login, String title, int viewers, String language, String url) {
    }
}
