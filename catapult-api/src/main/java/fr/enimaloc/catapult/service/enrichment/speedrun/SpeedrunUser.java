package fr.enimaloc.catapult.service.enrichment.speedrun;

/** An account of speedrun.com; {@code twitchUri} is the Twitch link of its profile, null when it has none. */
public record SpeedrunUser(String id, String name, String url, String twitchUri) {
}
