package fr.enimaloc.catapult.service.battlenet;

import bgs.protocol.RpcProto.Header;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import fr.enimaloc.catapult.bgs.core.WireExchange;
import fr.enimaloc.catapult.bgs.core.WireTap;
import fr.enimaloc.catapult.bgs.proto.BgsServices;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.debug.SessionDebug;
import fr.enimaloc.catapult.service.debug.wire.ProtobufJson;
import fr.enimaloc.catapult.service.debug.wire.WireKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Records, for the members in debug mode linked to one bot, the BGS exchanges of its session that name them: one
 * entry per call and its reply (or server call and our ack), the header and body as JSON next to the raw bytes.
 * Whom a frame names is read from its bodies decoded with their schema (exact values).
 *
 * <p>Only the methods naming a single friend are recorded, see {@link #RECORDED}, and only when each body decodes
 * with its schema and names a single entry, see {@link #body}.
 */
@Slf4j
public final class BgsWireRecorder implements WireTap {

    /** The HTTP-like header holding the BGS header as JSON. */
    static final String HEADER = "Bgs-Header";

    /**
     * The only methods recorded: each names a single friend (or invitation). A frame naming several people (friend
     * lists, subscriptions and their failures, batch presence subscriptions) would expose the friends of the bot
     * who are not members, whom the masking of other members does not know; login, connection and account frames
     * can carry secrets; unknown methods and raw calls cannot be vouched for. All of those are dropped before being
     * decoded.
     */
    private static final List<ServiceMethod<?, ?>> RECORDED = List.of(
            BgsServices.FRIENDS_LISTENER_ON_FRIEND_ADDED,
            BgsServices.FRIENDS_LISTENER_ON_FRIEND_REMOVED,
            BgsServices.FRIENDS_LISTENER_ON_RECEIVED_INVITATION_ADDED,
            BgsServices.FRIENDS_LISTENER_ON_RECEIVED_INVITATION_REMOVED,
            BgsServices.FRIENDS_LISTENER_ON_SENT_INVITATION_ADDED,
            BgsServices.FRIENDS_LISTENER_ON_SENT_INVITATION_REMOVED,
            BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE,
            BgsServices.PRESENCE_LISTENER_ON_PRESENCE_STATE_UPDATED,
            BgsServices.FRIENDS_SEND_INVITATION,
            BgsServices.FRIENDS_ACCEPT_INVITATION,
            BgsServices.FRIENDS_REVOKE_INVITATION,
            BgsServices.FRIENDS_IGNORE_INVITATION,
            BgsServices.FRIENDS_REMOVE_FRIEND);

    private final UUID serviceAccountId;
    private final SessionDebug debug;

    /** A body decoded with its schema: its JSON and its bytes, both null when it is empty. */
    private record Body(ObjectNode json, byte[] raw) {
        static final Body NONE = new Body(null, null);
    }

    public BgsWireRecorder(UUID serviceAccountId, SessionDebug debug) {
        this.serviceAccountId = serviceAccountId;
        this.debug = debug;
    }

    @Override
    public boolean wants() {
        return debug.watches(serviceAccountId);
    }

    @Override
    public void onExchange(WireExchange exchange) {
        try {
            ServiceMethod<?, ?> method = recorded(exchange.requestHeader());
            if (method == null) {
                return;
            }
            Body request = body(exchange.requestBody(), method.requestParser());
            Body reply = request == null ? null : body(exchange.replyBody(), method.responseParser());
            if (reply == null) {
                return;
            }
            Predicate<String> concerns = WireKeys.concerns(WireKeys.of(request.json()))
                    .or(WireKeys.concerns(WireKeys.of(reply.json())));
            debug.exchange(serviceAccountId, concerns, exchange.initiatedLocally() ? Direction.OUT : Direction.IN,
                    () -> raw(exchange, request, reply), "bgs." + method.service() + "." + method.methodId());
        } catch (RuntimeException e) {
            log.warn("Could not record a BGS exchange of {} for the members in debug mode: {}", serviceAccountId,
                    e.toString());
        }
    }

    /** The recorded method the header calls, or null when the exchange must not be recorded. */
    private static ServiceMethod<?, ?> recorded(Header header) {
        if (header == null) {
            return null;
        }
        return RECORDED.stream()
                .filter(method -> method.serviceHash() == header.getServiceHash()
                        && method.methodId() == header.getMethodId())
                .findFirst().orElse(null);
    }

    /**
     * The body parsed with its schema, or null when the exchange must not be recorded: the body does not parse, holds
     * fields its schema does not know (they could name anyone), or one of its top-level repeated messages has several
     * entries (a batch could name friends who are not members, whom the masking of other members does not know).
     */
    private static Body body(ByteString bytes, Parser<? extends Message> parser) {
        if (bytes == null || bytes.isEmpty()) {
            return Body.NONE;
        }
        if (parser == null) {
            return null;
        }
        Message message;
        try {
            message = parser.parsePartialFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
        if (!fullyKnown(message) || batches(message)) {
            return null;
        }
        return new Body(ProtobufJson.of(message), bytes.toByteArray());
    }

    /** Whether the message and every message it holds have no field missing from their schema. */
    private static boolean fullyKnown(Message message) {
        if (!message.getUnknownFields().asMap().isEmpty()) {
            return false;
        }
        for (Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
            if (field.getKey().getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            List<?> values = field.getKey().isRepeated() ? (List<?>) field.getValue() : List.of(field.getValue());
            for (Object value : values) {
                if (!fullyKnown((Message) value)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether one of the top-level repeated message fields holds more than one entry. */
    private static boolean batches(Message message) {
        return message.getDescriptorForType().getFields().stream()
                .anyMatch(field -> field.isRepeated() && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                        && message.getRepeatedFieldCount(field) > 1);
    }

    private static RawExchange raw(WireExchange exchange, Body request, Body reply) {
        Header replyHeader = exchange.replyHeader();
        return new RawExchange(exchange.at(), "BGS", null, headers(exchange.requestHeader(), request),
                json(request), replyHeader == null ? null : replyHeader.getStatus(), headers(replyHeader, reply),
                json(reply), exchange.error(), exchange.durationNanos() / 1_000_000L, request.raw(), reply.raw());
    }

    /** The BGS header as JSON, its {@code ciid} masked, and the body's content type when it is JSON. */
    private static HttpHeaders headers(Header header, Body body) {
        if (header == null && body.json() == null) {
            return null;
        }
        HttpHeaders headers = new HttpHeaders();
        if (header != null) {
            ObjectNode json = ProtobufJson.of(header);
            if (json.has("ciid")) {
                json.put("ciid", "[ciid]");
            }
            headers.add(HEADER, json.toString());
        }
        if (body.json() != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return headers;
    }

    private static byte[] json(Body body) {
        return body.json() == null ? null : body.json().toString().getBytes(StandardCharsets.UTF_8);
    }
}
