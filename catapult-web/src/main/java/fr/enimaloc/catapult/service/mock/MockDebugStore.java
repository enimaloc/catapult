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
        // An incoming EventSub message, as catapult-api records it: no status, no headers, a success.
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(45, ChronoUnit.MINUTES), "IN", "twitch", "eventsub.notification",
                "WS", "wss://eventsub.wss.twitch.tv/ws", null, false, 12, false,
                null, "{\"metadata\":{\"message_type\":\"notification\"},\"payload\":{\"subscription\":{\"type\":\"stream.online\"}}}",
                null, null, null, "0f9e8d7c6b5a49382716f5e4d3c2b1a0"));
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(20, ChronoUnit.MINUTES), "OUT", "twitch", "getUsers",
                "GET", "https://api.twitch.tv/helix/users?id=5000001&id=[membre]", 200, false, 61, true,
                "{\"Authorization\":\"[masked]\"}", null, "{\"Content-Type\":\"application/json\"}",
                "{\"data\":[{\"login\":\"viewer001\"}]}", null, "c0ffee00c0ffee00c0ffee00c0ffee00"));
        // more than a page of them, for the pagination
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(10, ChronoUnit.MINUTES), "OUT", "igdb", "games",
                "POST", "https://api.igdb.com/v4/games", null, true, 5000, false,
                "{\"Client-ID\":\"[masked]\",\"Authorization\":\"[masked]\"}", "fields name; where id = 119133;",
                null, null, "Read timed out", null));
        seeded.add(new DebugExchangeDto(UUID.randomUUID(), now.minus(5, ChronoUnit.MINUTES), "OUT", "minecraft", "friends",
                "GET", "https://api.minecraftservices.com/friends", 200, false, 143, false,
                "{\"Authorization\":\"[masked]\"}", null, "{\"Content-Type\":\"application/json\"}",
                "{\"friends\":[{\"name\":\"[membre]\"},{\"name\":\"Notch\"}]}", null, null));
        seeded.replaceAll(MockDebugStore::withComputedError);
        exchanges.put(seededMember, seeded);
    }

    /**
     * Whether catapult-api counts the exchange as failed: an outgoing call without status, with a status from 400
     * on, or with an error; an incoming message, which has no status, only with an error.
     */
    static boolean isError(String direction, Integer status, String errorMessage) {
        if (errorMessage != null) return true;
        if (status == null) return !"IN".equalsIgnoreCase(direction);
        return status >= 400;
    }

    private static DebugExchangeDto withComputedError(DebugExchangeDto e) {
        return new DebugExchangeDto(e.id(), e.occurredAt(), e.direction(), e.service(), e.operation(), e.method(),
                e.url(), e.status(), isError(e.direction(), e.status(), e.errorMessage()), e.durationMs(), e.filtered(),
                e.requestHeaders(), e.requestBody(), e.responseHeaders(), e.responseBody(), e.errorMessage(), e.traceId());
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
