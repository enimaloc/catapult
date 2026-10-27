package fr.enimaloc.catapult.service.debug;

/**
 * Supplies the id of the trace the current thread belongs to, stored with each recorded exchange. It may return
 * null when there is no current trace, in which case the exchange is stored without a trace id.
 */
@FunctionalInterface
public interface TraceIdSource {
    /** The current trace id, or null when there is none. */
    String currentTraceId();
}
