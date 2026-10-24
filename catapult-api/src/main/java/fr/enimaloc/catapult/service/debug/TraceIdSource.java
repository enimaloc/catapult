package fr.enimaloc.catapult.service.debug;

/**
 * Supplies the id of the trace the current thread belongs to, stored with each recorded exchange. Without such a
 * bean (no tracing on the classpath), exchanges are stored without a trace id.
 */
@FunctionalInterface
public interface TraceIdSource {
    /** The current trace id, or null when there is none. */
    String currentTraceId();
}
