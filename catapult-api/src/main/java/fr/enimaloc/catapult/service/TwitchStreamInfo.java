package fr.enimaloc.catapult.service;

import java.time.Instant;

/** {@code null} startedAt means the streamer is currently offline. */
public record TwitchStreamInfo(String title, String category, int viewers, Instant startedAt) {
}
