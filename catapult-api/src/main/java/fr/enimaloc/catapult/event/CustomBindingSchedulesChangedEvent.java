package fr.enimaloc.catapult.event;

import java.util.UUID;

/** The user's eligible custom binding schedules may have changed (e.g. premium granted or revoked). */
public record CustomBindingSchedulesChangedEvent(UUID userId) {
}
