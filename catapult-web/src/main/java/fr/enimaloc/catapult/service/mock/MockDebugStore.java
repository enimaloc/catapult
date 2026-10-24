package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.service.DebugExchangeFilter;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The mock's member debug mode: per-member state and recorded exchanges, answering like catapult-api
 * (an exchange of another member is unknown, enabling an active member is refused). Nothing is
 * recorded for real: switching it on starts from the exchanges already there, as a reactivation does.
 */
class MockDebugStore {
    static final int PAGE_SIZE = 5;
    private static final long PURGE_DAYS = 30;

    private record State(boolean revealSecrets, Instant enabledAt, Instant disabledAt) {}

    private final Clock clock;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, List<DebugExchangeDto>> exchanges = new HashMap<>();

    MockDebugStore(Clock clock, UUID seededMember) {
        this.clock = clock;
        Instant now = clock.instant();
        states.put(seededMember, new State(false, now.minus(2, ChronoUnit.HOURS), null));
        List<DebugExchangeDto> seeded = new ArrayList<>();
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(90, ChronoUnit.MINUTES), "OUT", "twitch", "getChannel",
                "GET", "https://api.twitch.tv/helix/channels?broadcaster_id=5000001", 200, false, 84, false,
                "{\"Authorization\":\"[masked]\"}", null, "{\"Content-Type\":\"application/json\"}",
                "{\"data\":[{\"broadcaster_name\":\"viewer001\",\"game_name\":\"Elden Ring\"}]}", null, "7f1c2b3a4d5e6f708192a3b4c5d6e7f8"));
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(75, ChronoUnit.MINUTES), "OUT", "steam", "getOwnedGames",
                "GET", "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1?key=[masked]", 500, true, 1203, false,
                "{}", null, "{\"Content-Type\":\"text/html\"}", "<html>Internal Server Error</html>",
                "Steam answered 500", "a1b2c3d4e5f60718293a4b5c6d7e8f90"));
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(45, ChronoUnit.MINUTES), "IN", "twitch", "eventsub.notification",
                "POST", "https://catapult.example/api/eventsub/callback", 204, false, 12, false,
                "{\"Twitch-Eventsub-Message-Signature\":\"[masked]\"}", "{\"subscription\":{\"type\":\"stream.online\"}}",
                "{}", null, null, "0f9e8d7c6b5a49382716f5e4d3c2b1a0"));
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(20, ChronoUnit.MINUTES), "OUT", "twitch", "getUsers",
                "GET", "https://api.twitch.tv/helix/users?id=5000001&id=[membre]", 200, false, 61, true,
                "{\"Authorization\":\"[masked]\"}", null, "{\"Content-Type\":\"application/json\"}",
                "{\"data\":[{\"login\":\"viewer001\"}]}", null, "c0ffee00c0ffee00c0ffee00c0ffee00"));
        exchanges.put(seededMember, seeded);
    }

    synchronized MemberDebugStateDto state(UUID member) {
        State state = states.get(member);
        if (state == null) return null;
        boolean active = state.disabledAt() == null;
        return new MemberDebugStateDto(active, state.revealSecrets(), state.enabledAt(), null, state.disabledAt(),
                active ? null : state.disabledAt().plus(PURGE_DAYS, ChronoUnit.DAYS), exchanges.getOrDefault(member, List.of()).size());
    }

    synchronized boolean active(UUID member) {
        State state = states.get(member);
        return state != null && state.disabledAt() == null;
    }

    /** 204, or 409 when it is already on. */
    synchronized int enable(UUID member, boolean revealSecrets) {
        if (active(member)) return 409;
        states.put(member, new State(revealSecrets, clock.instant(), null));
        return 204;
    }

    /** 204, or 404 when it never was on. */
    synchronized int disable(UUID member) {
        State state = states.get(member);
        if (state == null) return 404;
        if (state.disabledAt() == null) {
            states.put(member, new State(state.revealSecrets(), state.enabledAt(), clock.instant()));
        }
        return 204;
    }

    /** Deletes the member's exchanges; the mode itself is left as is, as catapult-api does. */
    synchronized int clear(UUID member) {
        exchanges.remove(member);
        return 204;
    }

    synchronized AdminPage<DebugExchangeSummaryDto> list(UUID member, DebugExchangeFilter filter, int page) {
        List<DebugExchangeSummaryDto> matching = exchanges.getOrDefault(member, List.of()).stream()
                .filter(e -> filter.service() == null || filter.service().isBlank() || e.service().equals(filter.service()))
                .filter(e -> filter.direction() == null || filter.direction().isBlank() || e.direction().equalsIgnoreCase(filter.direction()))
                .filter(e -> filter.outcome() == null || filter.outcome().isBlank()
                        || e.error() == "error".equals(filter.outcome().toLowerCase(Locale.ROOT)))
                .filter(e -> filter.from() == null || !e.occurredAt().isBefore(filter.from()))
                .filter(e -> filter.to() == null || !e.occurredAt().isAfter(filter.to()))
                .sorted(Comparator.comparing(DebugExchangeDto::occurredAt).reversed())
                .map(e -> new DebugExchangeSummaryDto(e.id(), e.occurredAt(), e.direction(), e.service(), e.operation(),
                        e.method(), e.url(), e.status(), e.error(), e.durationMs(), e.filtered()))
                .toList();
        int number = Math.max(page, 0);
        int from = Math.min(number * PAGE_SIZE, matching.size());
        return new AdminPage<>(matching.subList(from, Math.min(from + PAGE_SIZE, matching.size())), number,
                (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE, matching.size());
    }

    /** Null when it is not this member's. */
    synchronized DebugExchangeDto exchange(UUID member, UUID exchangeId) {
        return exchanges.getOrDefault(member, List.of()).stream().filter(e -> e.id().equals(exchangeId)).findFirst().orElse(null);
    }

    synchronized List<String> services(UUID member) {
        return List.copyOf(new TreeSet<>(exchanges.getOrDefault(member, List.<DebugExchangeDto>of()).stream()
                .map(DebugExchangeDto::service).toList()));
    }
}
