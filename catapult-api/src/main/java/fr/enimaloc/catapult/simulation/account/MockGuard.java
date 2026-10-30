package fr.enimaloc.catapult.simulation.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * The {@code mock} profile's lock: it makes fictitious accounts, signs them in without Twitch and simulates every
 * platform, so it only starts as agreed, {@code dev,mock}: without the {@code dev} profile it refuses to start. A
 * {@code sentry.environment} of {@code production} or {@code prod} (say a deployment's {@code SENTRY_ENVIRONMENT}
 * overriding {@code dev}'s {@code development}) is refused too.
 */
@Component
@Profile("mock")
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class MockGuard {

    private static final Set<String> PRODUCTION = Set.of("production", "prod");

    public MockGuard(Environment environment, @Value("${sentry.environment:}") String sentryEnvironment) {
        if (!environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException("The mock profile only starts along the dev profile (dev,mock), not with "
                    + String.join(",", environment.getActiveProfiles()));
        }
        if (PRODUCTION.contains(sentryEnvironment.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("The mock profile refuses to start with sentry.environment="
                    + sentryEnvironment + ": it is never run in production");
        }
    }
}
