package fr.enimaloc.catapult.service.enrichment.twitch;

import java.util.List;

/** What is going on in a game's Twitch category right now: lives, viewers and recent clips. */
public record TwitchActivity(String categoryId, String categoryName, int liveCount, long viewers,
                             boolean approximate, List<TwitchLives.Stream> topStreams,
                             List<TwitchClips.Clip> topClips) {
}
