package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Payload;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;
import fr.enimaloc.catapult.service.ankama.chat.ChatWireExchange;
import fr.enimaloc.catapult.service.ankama.chat.ChatWireTap;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.debug.SessionDebug;
import fr.enimaloc.catapult.service.debug.wire.ProtobufWireDecoder;
import fr.enimaloc.catapult.service.debug.wire.WireKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Records, for the members in debug mode linked to one bot, the chat frames of its session that name them: one
 * entry per request and its response, or per pushed event, the frame header and the payload as JSON next to the
 * payload's raw bytes. Payloads are decoded without schema (field numbers); whom a frame names is read from its
 * decoded payload only (exact values, {@code name#tag} recombined from sibling fields). A frame with a payload that
 * does not decode is dropped: searching its raw bytes cannot tell a member's identifier from a longer value.
 *
 * <p>Only the per-friend commands and events {@link ChatMessages} knows are recorded: any other payload id is
 * dropped before being decoded, and a response follows its request. The friend and invite lists are never recorded:
 * they name every friend of the bot, strangers included, whom the masking of other members cannot know.
 */
@Slf4j
public final class AnkamaWireRecorder implements ChatWireTap {

    /** The HTTP-like header holding the chat frame header as JSON. */
    static final String HEADER = "Chat-Header";

    /** The list requests and their responses, never recorded (see the class comment). */
    private static final Set<String> LISTS = Set.of("LIST_USER_FRIENDS_CMD", "LIST_USER_FRIEND_INVITES_CMD",
            "FRIEND_LIST", "FRIEND_INVITE_LIST");
    /** The recorded payload ids and their name: the commands and events {@link ChatMessages} declares. */
    private static final Map<Integer, String> RECORDED = recorded();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final UUID serviceAccountId;
    private final SessionDebug debug;

    /** A decoded payload: its id, its JSON (null when undecodable or empty), its bytes (null when empty). */
    private record Body(Integer id, ObjectNode json, byte[] raw) {
        static final Body NONE = new Body(null, null, null);
    }

    public AnkamaWireRecorder(UUID serviceAccountId, SessionDebug debug) {
        this.serviceAccountId = serviceAccountId;
        this.debug = debug;
    }

    @Override
    public boolean wants() {
        return debug.watches(serviceAccountId);
    }

    @Override
    public void onExchange(ChatWireExchange exchange) {
        try {
            Payload payload = payload(exchange.request());
            String name = payload == null ? null : RECORDED.get(payload.id());
            if (name == null) {
                return;
            }
            Body request = body(payload);
            Body response = exchange.response() == null ? Body.NONE : body(exchange.response().payload());
            if (undecodable(request) || undecodable(response)) {
                return;
            }
            Predicate<String> concerns = concerns(request).or(concerns(response));
            debug.exchange(serviceAccountId, concerns, exchange.initiatedLocally() ? Direction.OUT : Direction.IN,
                    () -> raw(exchange, request, response), "chat." + name);
        } catch (RuntimeException e) {
            log.warn("Could not record a chat exchange of {} for the members in debug mode: {}", serviceAccountId,
                    e.toString());
        }
    }

    /** The payload of our request or of the pushed event; a response is only seen with its request. */
    private static Payload payload(ChatFrames.Frame frame) {
        if (frame instanceof ChatFrames.Request request) {
            return request.payload();
        }
        return frame instanceof ChatFrames.Event event ? event.payload() : null;
    }

    /** The {@code *_CMD} and {@code *_EVT} payload ids among the constants of {@link ChatMessages}, lists aside. */
    private static Map<Integer, String> recorded() {
        return Arrays.stream(ChatMessages.class.getFields())
                .filter(field -> field.accessFlags().contains(AccessFlag.STATIC) && field.getType() == int.class)
                .filter(field -> field.getName().endsWith("_CMD") || field.getName().endsWith("_EVT"))
                .filter(field -> !LISTS.contains(field.getName()))
                .collect(Collectors.toUnmodifiableMap(AnkamaWireRecorder::value, Field::getName));
    }

    private static int value(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Who a payload names: its exact leaf values. */
    private static Predicate<String> concerns(Body body) {
        return WireKeys.concerns(WireKeys.of(body.json()));
    }

    /** Whether a payload has bytes that did not decode. */
    private static boolean undecodable(Body body) {
        return body.raw() != null && body.json() == null;
    }

    private static Body body(Payload payload) {
        if (payload == null) {
            return Body.NONE;
        }
        byte[] raw = payload.data() == null || payload.data().length == 0 ? null : payload.data();
        return new Body(payload.id(), ProtobufWireDecoder.decode(raw).orElse(null), raw);
    }

    private static RawExchange raw(ChatWireExchange exchange, Body request, Body response) {
        ChatFrames.Frame frame = exchange.request();
        Integer correlationId = frame instanceof ChatFrames.Request r ? r.correlationId() : null;
        HttpHeaders requestHeaders = headers(frame instanceof ChatFrames.Event ? "event" : "request", correlationId,
                null, request);
        ChatFrames.Response reply = exchange.response();
        HttpHeaders responseHeaders = reply == null ? null
                : headers("response", reply.correlationId(), reply.status(), response);
        return new RawExchange(exchange.at(), "CHAT", null, requestHeaders, json(request),
                reply == null ? null : reply.status(), responseHeaders, json(response), exchange.error(),
                exchange.durationNanos() / 1_000_000L, request.raw(), response.raw());
    }

    /** The frame header as JSON, and the payload's content type when it decoded. */
    private static HttpHeaders headers(String kind, Integer correlationId, Integer status, Body body) {
        ObjectNode header = NODES.objectNode();
        header.put("kind", kind);
        if (correlationId != null) {
            header.put("correlationId", correlationId);
        }
        if (status != null) {
            header.put("status", status);
        }
        if (body.id() != null) {
            header.put("payloadId", body.id());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(HEADER, header.toString());
        if (body.json() != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return headers;
    }

    private static byte[] json(Body body) {
        return body.json() == null ? null : body.json().toString().getBytes(StandardCharsets.UTF_8);
    }
}
