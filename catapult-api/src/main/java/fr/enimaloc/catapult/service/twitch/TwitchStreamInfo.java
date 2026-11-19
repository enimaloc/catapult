package fr.enimaloc.catapult.service.twitch;

import java.time.Instant;

/** {@code streamId} identifies the live stream (and its VOD); {@code null} startedAt means the streamer is currently offline. */
public record TwitchStreamInfo(String streamId, String title, String category, int viewers, Instant startedAt) {
}
