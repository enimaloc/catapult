package fr.enimaloc.catapult.service.dryrun;

import java.util.UUID;

/** {@code userId} just left dry run, as this instance sees it (switched off here, or found gone on a reload). */
public record DryRunEndedEvent(UUID userId) {
}
