package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange;

import java.util.UUID;

/**
 * Told about every exchange stored for a member in debug mode, on the recorder's thread and with that member as
 * {@link DebugContext#subject()}. A listener that throws is logged and skipped.
 */
public interface DebugExchangeListener {
    void onRecorded(UUID userId, DebugExchange exchange);
}
