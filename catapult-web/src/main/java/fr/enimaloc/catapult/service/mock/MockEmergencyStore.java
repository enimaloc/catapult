package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.emergency.ChannelEmergencyDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStatusDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The mock's emergency stops: an Xbox stop on the whole instance for the banner, and a Steam stop on the channel
 * that "Resume" lifts (the global one stays, like catapult-api).
 */
class MockEmergencyStore {
    private final Clock clock;
    private final EmergencyStopDto global;
    private final Set<String> resumed = new HashSet<>();

    MockEmergencyStore(Clock clock) {
        this.clock = clock;
        Instant now = clock.instant();
        this.global = new EmergencyStopDto(UUID.nameUUIDFromBytes("mock-global".getBytes()), "XBOX", "GLOBAL",
                "THRESHOLD", null, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(23)), null, null);
    }

    EmergencyStatusDto status() {
        return new EmergencyStatusDto(List.of(global));
    }

    synchronized ChannelEmergencyDto channel(String username, boolean botEnabled) {
        List<EmergencyStopDto> stops = List.of();
        if (!resumed.contains(username)) {
            Instant now = clock.instant();
            stops = List.of(new EmergencyStopDto(UUID.nameUUIDFromBytes(("mock-" + username).getBytes()), "STEAM",
                    "CHANNEL", "CHANNEL_REPORT", username, now.minus(Duration.ofMinutes(10)),
                    now.plus(Duration.ofHours(24)), null, null));
        }
        return new ChannelEmergencyDto(botEnabled, stops, List.of(global));
    }

    synchronized void resume(String username) {
        resumed.add(username);
    }
}
