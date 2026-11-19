package fr.enimaloc.catapult.service.emergency;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Settings of the provider emergency stop. */
@Component
@ConfigurationProperties("app.emergency")
@Getter
@Setter
public class EmergencyProperties {
    /** Distinct channels (and authors) that must report a provider to stop it instance-wide. */
    private int threshold = 3;
    /** Sliding window in which reports are counted. */
    private Duration window = Duration.ofMinutes(15);
    /** How long a stop lasts, extended by each new report. */
    private Duration stopDuration = Duration.ofHours(24);
    /** How far before the report the VOD link is rewound. */
    private Duration vodRewind = Duration.ofMinutes(2);
    /** How long after a report its VOD is still looked for. */
    private Duration vodResolveMaxAge = Duration.ofDays(14);
    /** How long reports are kept. */
    private Duration reportRetention = Duration.ofDays(90);
}
