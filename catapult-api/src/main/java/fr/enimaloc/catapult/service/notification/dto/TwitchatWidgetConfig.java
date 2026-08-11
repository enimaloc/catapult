package fr.enimaloc.catapult.service.notification.dto;

/**
 * OBS-websocket connection info pushed live to an already-open widget page whenever the
 * streamer edits their Twitchat settings, so the relay can reconnect with the new
 * host/port/password without needing a manual page reload. {@code obsPassword} is the
 * decrypted plaintext — the same trust boundary as the initial page-load config fetch
 * (only reachable by someone who already holds the widget token).
 */
public record TwitchatWidgetConfig(String obsHost, Integer obsPort, String obsPassword) {
}
