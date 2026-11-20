package fr.enimaloc.catapult.common.dto;

import java.time.Instant;

/** Whether a member is in dry run (Catapult writes nothing public on their Twitch channel), and since when (null when off). */
public record DryRunStateDto(boolean active, Instant since) {

    public static final DryRunStateDto OFF = new DryRunStateDto(false, null);
}
