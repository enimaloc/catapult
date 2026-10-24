package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Records, for the members in debug mode, what the outbound interceptor cannot attribute on its own: their share of
 * a call made for many members at once, and the messages catapult receives for them. Nothing here ever fails or
 * holds up the work it observes; with no member concerned in debug mode it costs a map lookup per member.
 */
@Slf4j
@Component
public class DebugRecording {

    /** Records nothing: what a class uses until (or unless) the real bean is injected. */
    public static final DebugRecording OFF = new DebugRecording(null, null);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A member of a grouped call, and the identifier the call knows it by (steamId, twitchId, profile id). */
    public record Member(UUID userId, String key) {
    }

    private final DebugMemberRegistry registry;
    private final DebugExchangeRecorder recorder;

    @Autowired
    public DebugRecording(DebugMemberRegistry registry, DebugExchangeRecorder recorder) {
        this.registry = registry;
        this.recorder = recorder;
    }

    /** Whether any member at all is in debug mode: lets a caller skip finding out whom a grouped call is for. */
    public boolean anyMemberInDebug() {
        if (registry == null) {
            return false;
        }
        try {
            return registry.hasAny();
        } catch (RuntimeException e) {
            log.warn("Could not tell whether a member is in debug mode: {}", e.toString());
            return false;
        }
    }

    /**
     * Runs {@code call}, a call made for all of {@code members} at once. When some of them are in debug mode, its
     * exchanges are captured instead of attributed, and each of those members gets its own share of each exchange
     * ({@code share} applied to it with the member's key; a null share records nothing for it), marked filtered.
     * Without any, the call runs exactly as it would without debug mode. Work {@code call} hands to wrapped
     * executors must be finished when it returns, or it is not captured.
     */
    public <T> T batch(String operation, Collection<Member> members,
                       BiFunction<RawExchange, String, RawExchange> share, Supplier<T> call) {
        List<Member> debugged = debugged(members);
        if (debugged.isEmpty()) {
            return call.get();
        }
        RuntimeException[] failure = new RuntimeException[1];
        DebugContext.Captured<T> captured = DebugContext.capture(() -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                failure[0] = e;
                return null;
            }
        });
        recordShares(operation, debugged, share, captured.exchanges());
        if (failure[0] != null) {
            throw failure[0];
        }
        return captured.result();
    }

    private List<Member> debugged(Collection<Member> members) {
        if (registry == null || recorder == null || members == null || members.isEmpty()) {
            return List.of();
        }
        try {
            if (!registry.hasAny()) {
                return List.of();
            }
            return members.stream()
                    .filter(m -> m != null && m.userId() != null && m.key() != null
                            && registry.modeOf(m.userId()).isPresent())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Could not tell which members of a grouped call are in debug mode: {}", e.toString());
            return List.of();
        }
    }

    private void recordShares(String operation, List<Member> members,
                              BiFunction<RawExchange, String, RawExchange> share, List<RawExchange> exchanges) {
        for (RawExchange raw : exchanges) {
            for (Member member : members) {
                try {
                    RawExchange mine = share.apply(raw, member.key());
                    if (mine != null) {
                        recorder.record(member.userId(), Direction.OUT, mine, operation, true);
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not record the share of {} in a grouped call: {}", member.userId(), e.toString());
                }
            }
        }
    }

    /**
     * Records a message catapult received for {@code userId} (no response), when that member is in debug mode.
     *
     * @param service   the service it came from ({@code twitch}, {@code steam})
     * @param method    {@code WS} for a WebSocket message, else the HTTP method
     * @param uri       where it was received (the socket's URL, or the full request URL)
     * @param body      the message itself, or null
     * @param operation names the message; only evaluated for a member in debug mode, and may return null
     */
    public void inbound(UUID userId, String service, String method, URI uri, String body,
                        Supplier<String> operation) {
        if (registry == null || recorder == null || userId == null) {
            return;
        }
        try {
            if (registry.modeOf(userId).isEmpty()) {
                return;
            }
            RawExchange raw = new RawExchange(Instant.now(), method, uri, null,
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8), null, null, null, null, 0);
            recorder.record(userId, Direction.IN, raw, nameOf(operation), false, service);
        } catch (RuntimeException e) {
            log.warn("Could not record a message received for {}: {}", userId, e.toString());
        }
    }

    private static String nameOf(Supplier<String> operation) {
        try {
            return operation == null ? null : operation.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code eventsub.<subscription_type>} for a notification (or revocation), else {@code eventsub.<message_type>};
     * null when the message is not EventSub JSON.
     */
    public static String eventSubOperation(String message) {
        try {
            JsonNode metadata = JSON.readTree(message).path("metadata");
            String subscription = metadata.path("subscription_type").asString("");
            if (!subscription.isEmpty()) {
                return "eventsub." + subscription;
            }
            String type = metadata.path("message_type").asString("");
            return type.isEmpty() ? null : "eventsub." + type;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
