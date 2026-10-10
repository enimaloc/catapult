package fr.enimaloc.catapult.service.twitch;

import java.time.Instant;

public record TwitchUserProfile(String displayName, Instant createdAt) {
}
