package fr.enimaloc.catapult.service;

import java.time.Instant;

public record TwitchUserProfile(String displayName, Instant createdAt) {
}
