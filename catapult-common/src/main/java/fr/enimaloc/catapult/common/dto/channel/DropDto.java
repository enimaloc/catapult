package fr.enimaloc.catapult.common.dto.channel;

import java.time.Instant;

/** A Twitch drops campaign running for a channel on a category: its name, its end, its page (null when none). */
public record DropDto(String name, Instant endAt, String detailsUrl) {}
