package fr.enimaloc.catapult.site;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Whether this site is the active one: the public DNS name must resolve, on every authoritative server, to this
 * site's public IP, and the database must be writable. A confirmed contrary answer means standby at once; a doubt (no
 * DNS answer, a database role that could not be read) only once it outlasts the grace period.
 */
@Slf4j
@Component
public class SiteRole {

    public record SiteStatus(boolean configured, boolean active, String reason, Instant since) {}

    private final SiteProperties properties;
    private final DnsTargetResolver resolver;
    private final DatabaseRoleProbe databaseProbe;
    private final ApplicationEventPublisher events;
    private final MeterRegistry registry;
    private final Clock clock;

    private volatile boolean active;
    private volatile String reason = "not checked yet";
    private volatile Instant since;
    private volatile Instant lastSuccess;
    private volatile boolean doubtWarned;

    @Autowired
    public SiteRole(SiteProperties properties, DnsTargetResolver resolver, DatabaseRoleProbe databaseProbe,
                    ApplicationEventPublisher events, MeterRegistry registry) {
        this(properties, resolver, databaseProbe, events, registry, Clock.systemUTC());
    }

    SiteRole(SiteProperties properties, DnsTargetResolver resolver, DatabaseRoleProbe databaseProbe,
             ApplicationEventPublisher events, MeterRegistry registry, Clock clock) {
        this.properties = properties;
        this.resolver = resolver;
        this.databaseProbe = databaseProbe;
        this.events = events;
        this.registry = registry;
        this.clock = clock;
        this.since = clock.instant();
        Gauge.builder("catapult.site.active", this, r -> r.isActive() ? 1 : 0)
            .description("1 when this site is the active one, 0 on standby")
            .register(registry);
    }

    public boolean isActive() {
        return !properties.configured() || active;
    }

    public void requireActive(String action) {
        if (!isActive()) {
            throw new StandbyException(action);
        }
    }

    public SiteStatus status() {
        if (!properties.configured()) {
            return new SiteStatus(false, true, "site guard not configured", since);
        }
        return new SiteStatus(true, active, reason, since);
    }

    @Scheduled(fixedDelayString = "${catapult.site.check-interval-ms:20000}")
    public void check() {
        if (!properties.configured()) {
            return;
        }
        Instant now = clock.instant();
        Set<String> addresses = null;
        Exception dnsFailure = null;
        try {
            addresses = resolver.resolveA(properties.getDnsName());
        } catch (IOException | RuntimeException e) {
            dnsFailure = e;
        }
        // Probed even when DNS failed: a confirmed replica must not wait for DNS to come back
        DatabaseRole databaseRole = probeDatabase();
        if (addresses != null && databaseRole != DatabaseRole.UNKNOWN) {
            lastSuccess = now;
            doubtWarned = false;
        }
        if (databaseRole == DatabaseRole.IN_RECOVERY) {
            transition(false, "database is in recovery");
        } else if (addresses != null && !addresses.equals(Set.of(properties.getPublicIp()))) {
            transition(false, properties.getDnsName() + " resolves to " + addresses);
        } else if (addresses == null) {
            doubt(now, "DNS resolution failing: " + dnsFailure.getMessage(), dnsFailure);
        } else if (databaseRole == DatabaseRole.UNKNOWN) {
            doubt(now, "database role unknown", null);
        } else {
            transition(true, properties.getDnsName() + " points at this site and the database is writable");
        }
    }

    private DatabaseRole probeDatabase() {
        try {
            return databaseProbe.role();
        } catch (RuntimeException e) {
            log.debug("Database role probe failed", e);
            return DatabaseRole.UNKNOWN;
        }
    }

    /**
     * A failed DNS resolution or database probe: keeps the current state while within the grace period of the last
     * fully successful check, then goes standby.
     */
    private void doubt(Instant now, String why, Exception cause) {
        if (lastSuccess != null && Duration.between(lastSuccess, now).toMillis() < properties.getGraceMs()) {
            log.debug("{}, keeping the current state within the grace period", why, cause);
            return;
        }
        if (!doubtWarned) {
            doubtWarned = true;
            log.warn("{} beyond the grace period, going standby", why);
        }
        transition(false, why + " beyond the grace period");
    }

    private synchronized void transition(boolean toActive, String why) {
        reason = why;
        if (toActive == active) {
            return;
        }
        active = toActive;
        since = clock.instant();
        registry.counter("catapult.site.transitions", "to", toActive ? "active" : "standby").increment();
        log.info("Site is now {}: {}", toActive ? "active" : "standby", why);
        events.publishEvent(new SiteRoleChangedEvent(this, toActive));
    }
}
