package fr.enimaloc.catapult.perf;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code perf} profile's lock: it opens a test login, so it only starts on a stack that cannot be mistaken for
 * production:
 * <ul>
 *   <li>{@code catapult.perf.enabled=true} (never in {@code application-perf.properties}): a stray {@code perf}
 *   profile on a real deployment fails the startup;</li>
 *   <li>{@code sentry.environment=perf}: a production environment variable cannot file perf runs under
 *   production.</li>
 * </ul>
 */
@Component
@Profile("perf")
public class PerfGuard {

    private static final String SENTRY_ENVIRONMENT = "perf";

    public PerfGuard(
            @Value("${catapult.perf.enabled:false}") boolean enabled,
            @Value("${sentry.environment:}") String sentryEnvironment) {
        if (!enabled) {
            throw new IllegalStateException("The perf profile needs catapult.perf.enabled=true");
        }
        if (!SENTRY_ENVIRONMENT.equals(sentryEnvironment)) {
            throw new IllegalStateException("The perf profile needs sentry.environment=perf, not "
                    + (sentryEnvironment.isBlank() ? "none" : sentryEnvironment));
        }
    }
}
