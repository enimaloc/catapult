package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.emergency.ChannelEmergencyDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyReportDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStatusDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The mock's emergency stops: an Xbox stop on the whole instance for the banner, a Steam stop on the channel that
 * "Resume" lifts (the global one stays, like catapult-api), and for the admin tab the stops the owner cuts and
 * resumes, a history, and a few reports (with a VOD, without, withdrawn, with a long activity log).
 */
class MockEmergencyStore {
    private static final Set<String> PROVIDERS = Set.of("STEAM", "XBOX", "BATTLENET", "MINECRAFT", "ANKAMA", "EPIC");
    private static final int REPORTS_PER_PAGE = 50;

    private final Clock clock;
    private final List<EmergencyStopDto> globals = new ArrayList<>();
    private final List<EmergencyStopDto> history = new ArrayList<>();
    private final Set<String> resumed = new HashSet<>();

    MockEmergencyStore(Clock clock) {
        this.clock = clock;
        Instant now = clock.instant();
        globals.add(new EmergencyStopDto(UUID.nameUUIDFromBytes("mock-global".getBytes()), "XBOX", "GLOBAL",
                "THRESHOLD", null, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(23)), null, null));
        history.add(new EmergencyStopDto(UUID.nameUUIDFromBytes("mock-history-1".getBytes()), "EPIC", "GLOBAL",
                "OWNER", null, now.minus(Duration.ofDays(3)), null, now.minus(Duration.ofDays(2)), "enimaloc"));
        history.add(new EmergencyStopDto(UUID.nameUUIDFromBytes("mock-history-2".getBytes()), "STEAM", "GLOBAL",
                "THRESHOLD", null, now.minus(Duration.ofDays(5)), now.minus(Duration.ofDays(4)), null, null));
    }

    synchronized EmergencyStatusDto status() {
        return new EmergencyStatusDto(List.copyOf(globals));
    }

    synchronized ChannelEmergencyDto channel(String username, boolean botEnabled) {
        List<EmergencyStopDto> stops = List.of();
        if (!resumed.contains(username)) {
            Instant now = clock.instant();
            stops = List.of(new EmergencyStopDto(UUID.nameUUIDFromBytes(("mock-" + username).getBytes()), "STEAM",
                    "CHANNEL", "CHANNEL_REPORT", username, now.minus(Duration.ofMinutes(10)),
                    now.plus(Duration.ofHours(24)), null, null));
        }
        return new ChannelEmergencyDto(botEnabled, stops, List.copyOf(globals));
    }

    synchronized void resume(String username) {
        resumed.add(username);
    }

    /** The active stops, or (not {@code active}) the lifted and expired ones, the latest first. */
    synchronized List<EmergencyStopDto> stops(boolean active) {
        return active ? List.copyOf(globals) : List.copyOf(history);
    }

    /** The owner's instance-wide stop; 400 for a provider that does not exist, else 200. */
    synchronized int ownerStop(String provider) {
        if (!"ALL".equals(provider) && !PROVIDERS.contains(provider)) return 400;
        globals.add(new EmergencyStopDto(UUID.randomUUID(), provider, "GLOBAL", "OWNER", null, clock.instant(), null,
                null, null));
        return 200;
    }

    /** Lifts the stops of a provider ({@code ALL}: every one); 400 for a provider that does not exist, else 200. */
    synchronized int ownerResume(String provider) {
        if (!"ALL".equals(provider) && !PROVIDERS.contains(provider)) return 400;
        Instant now = clock.instant();
        for (EmergencyStopDto stop : List.copyOf(globals)) {
            if (!"ALL".equals(provider) && !stop.provider().equals(provider)) continue;
            globals.remove(stop);
            history.addFirst(new EmergencyStopDto(stop.id(), stop.provider(), stop.scope(), stop.origin(),
                    stop.channelLogin(), stop.createdAt(), stop.expiresAt(), now, "enimaloc"));
        }
        return 200;
    }

    synchronized List<EmergencyReportDto> reports(int page) {
        if (page > 0) return List.of();
        Instant now = clock.instant();
        List<EmergencyReportDto> reports = List.of(
                report("mock-report-1", "streamer_one", "XBOX", "streamer_one", now.minus(Duration.ofMinutes(12)),
                        false, "https://www.twitch.tv/videos/2000000001?t=1h2m3s", context(3, true, "Just Chatting")),
                report("mock-report-2", "streamer_two", "XBOX", "mod_two", now.minus(Duration.ofMinutes(40)),
                        false, null, context(2, true, "Fortnite")),
                report("mock-report-3", "streamer_three", "STEAM", "streamer_three", now.minus(Duration.ofHours(5)),
                        false, null, context(1, false, null)),
                report("mock-report-4", "streamer_four", "EPIC", "streamer_four", now.minus(Duration.ofHours(9)),
                        true, "https://www.twitch.tv/videos/2000000004", context(60, true, "Minecraft")));
        return reports.size() > REPORTS_PER_PAGE ? reports.subList(0, REPORTS_PER_PAGE) : reports;
    }

    private static EmergencyReportDto report(String seed, String channel, String provider, String by, Instant at,
                                             boolean withdrawn, String vod, String context) {
        return new EmergencyReportDto(UUID.nameUUIDFromBytes(seed.getBytes()), channel, provider, by, at, withdrawn,
                vod, context);
    }

    /** A context as catapult-api collects it, with {@code lines} activity log lines. */
    private String context(int lines, boolean live, String category) {
        Instant now = clock.instant();
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i > 0) log.append(',');
            log.append("{\"timestamp\":\"").append(now.minus(Duration.ofSeconds(30L * (lines - i)))).append("\",")
                    .append("\"level\":\"").append(i % 7 == 6 ? "WARN" : "INFO").append("\",")
                    .append("\"message\":\"Game detected on the provider (").append(i + 1).append(")\"}");
        }
        String game = "{\"provider\":\"XBOX\",\"sourceId\":\"1234\",\"name\":\"Halo Infinite\"}";
        return "{\"activityLog\":[" + log + "],\"lastGame\":" + game + ",\"lastEndedGame\":null,\"twitchCategory\":"
                + (category == null ? "null" : "\"" + category + "\"") + ",\"live\":" + live + "}";
    }
}
