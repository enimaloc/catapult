package fr.enimaloc.catapult.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A Steam game's binding had its TWs resolved while the Steam store was rate limited: they miss what Steam would
 * have added, and are to be resolved again once it may be called ({@code until}).
 */
public record TwsMissedSteamEvent(UUID bindingId, Instant until) {}
