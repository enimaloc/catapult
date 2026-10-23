package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.service.degraded.DegradedView;

/** The degraded state the site follows changed: {@code view} is the new one. */
public record DegradedStateChangedEvent(DegradedView view) {}
