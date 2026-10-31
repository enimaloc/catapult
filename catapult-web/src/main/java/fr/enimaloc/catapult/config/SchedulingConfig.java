package fr.enimaloc.catapult.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods, such as the live stream's heartbeat
 * (NavEventStreamController#heartbeat). Kept apart from the application class so that test
 * slices do not pick it up.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
