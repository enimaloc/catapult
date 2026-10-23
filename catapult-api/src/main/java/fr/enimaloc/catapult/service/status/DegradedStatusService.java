package fr.enimaloc.catapult.service.status;

import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.config.DegradedMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class DegradedStatusService {

    private final boolean degraded;
    private final int pollingSeconds;
    private final boolean minecraftEnabled;
    private final long minecraftSyncMs;

    // The intervals are captured at construction, like the @Scheduled annotations they mirror
    // (same defaults), so they are what is actually applied.
    public DegradedStatusService(Environment env,
                                 @Value("${app.polling.interval-seconds:60}") int pollingSeconds,
                                 @Value("${minecraft.enabled:false}") boolean minecraftEnabled,
                                 @Value("${minecraft.friends-sync-interval-ms:300000}") long minecraftSyncMs) {
        this.degraded = env.matchesProfiles(DegradedMode.PROFILE);
        this.pollingSeconds = pollingSeconds;
        this.minecraftEnabled = minecraftEnabled;
        this.minecraftSyncMs = minecraftSyncMs;
    }

    public DegradedStatusDto status() {
        if (!degraded) {
            return new DegradedStatusDto(false, null, null);
        }
        Integer minecraftMinutes = minecraftEnabled ? ceilMinutes(minecraftSyncMs) : null;
        return new DegradedStatusDto(true, pollingSeconds, minecraftMinutes);
    }

    private static int ceilMinutes(long millis) {
        long perMinute = TimeUnit.MINUTES.toMillis(1);
        return (int) ((millis + perMinute - 1) / perMinute);
    }
}
