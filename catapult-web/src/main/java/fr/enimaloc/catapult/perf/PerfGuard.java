package fr.enimaloc.catapult.perf;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code perf} profile's lock: it opens a test login, so it only starts when {@code catapult.perf.enabled=true}
 * is also set (never in {@code application-perf.properties}). A stray {@code perf} profile on a real deployment
 * fails the startup instead.
 */
@Component
@Profile("perf")
public class PerfGuard {

    public PerfGuard(@Value("${catapult.perf.enabled:false}") boolean enabled) {
        if (!enabled) {
            throw new IllegalStateException("The perf profile needs catapult.perf.enabled=true");
        }
    }
}
