package fr.enimaloc.catapult.service.battlenet;

import bgs.protocol.RpcProto.Header;
import com.google.protobuf.ByteString;
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
import fr.enimaloc.catapult.service.debug.wire.ProtobufWireDecoder;
import fr.enimaloc.catapult.service.debug.wire.WireKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Records, for the members in debug mode linked to one bot, the BGS exchanges of its session that name them: one
 * entry per call and its reply (or server call and our ack), the header and body as JSON next to the raw bytes.
 * Whom a frame names is read from its decoded body (exact values) and, when a body could not be decoded with its
 * schema, searched in its raw bytes.
 *
 * <p>Only the methods naming a single friend are recorded, see {@link #RECORDED}.
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

    /** A decoded body: its JSON (null when none), its bytes (null when empty), whether its schema decoded it. */
    private record Body(ObjectNode json, byte[] raw, boolean typed) {
        static final Body NONE = new Body(null, null, true);
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
            Body reply = body(exchange.replyBody(), method.responseParser());
            Predicate<String> concerns = concerns(request).or(concerns(reply));
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
     * Who a body names: exact leaf values when its schema decoded it, else its raw bytes (and the leaves decoded
     * without schema).
     */
    private static Predicate<String> concerns(Body body) {
        Predicate<String> leaves = WireKeys.concerns(WireKeys.of(body.json()));
        return body.typed() ? leaves : leaves.or(WireKeys.rawConcerns(body.raw()));
    }

    /** The body parsed with its schema when there is one and it parses, else decoded without schema. */
    private static Body body(ByteString bytes, Parser<? extends Message> parser) {
        if (bytes == null || bytes.isEmpty()) {
            return Body.NONE;
        }
        byte[] raw = bytes.toByteArray();
        if (parser != null) {
            try {
                Message message = parser.parsePartialFrom(bytes);
                // A field missing from the schema could name anyone: only the raw bytes would tell
                return new Body(ProtobufJson.of(message), raw, message.getUnknownFields().asMap().isEmpty());
            } catch (InvalidProtocolBufferException e) {
                // decoded without schema below
            }
        }
        return new Body(ProtobufWireDecoder.decode(raw).orElse(null), raw, false);
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
