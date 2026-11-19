package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.common.dto.emergency.EmergencyStatusDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.service.ApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The global emergency stops the banner shows, read from catapult-api and kept for 30 seconds (every page asks).
 * When catapult-api doesn't answer there is no banner, never an error: the failed read is kept as long as a good one.
 */
@Slf4j
@Component
public class EmergencyState {
    private static final Duration TTL = Duration.ofSeconds(30);

    private final ApiService api;
    private final Clock clock;

    private volatile List<EmergencyStopDto> stops = List.of();
    private volatile Instant readAt;

    @Autowired
    public EmergencyState(ApiService api) {
        this(api, Clock.systemUTC());
    }

    EmergencyState(ApiService api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    /** The active global stops; empty when there are none or catapult-api can't say. Never null. */
    public List<EmergencyStopDto> globalStops() {
        Instant now = clock.instant();
        Instant last = readAt;
        if (last == null || !now.isBefore(last.plus(TTL))) {
            stops = read();
            readAt = now;
        }
        return stops;
    }

    private List<EmergencyStopDto> read() {
        try {
            EmergencyStatusDto status = api.emergencyStatus();
            return status == null || status.globalStops() == null ? List.of() : status.globalStops();
        } catch (RuntimeException e) {
            log.warn("Emergency stops not read", e);
            return List.of();
        }
    }
}
