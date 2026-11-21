package fr.enimaloc.catapult.service.enrichment.speedrun;

/** A game of speedrun.com; {@code twitchName} is the name the site sends to Twitch (null when absent). */
public record SpeedrunGame(String id, String name, String twitchName, String abbreviation, String url,
                           String coverUrl) {
}
