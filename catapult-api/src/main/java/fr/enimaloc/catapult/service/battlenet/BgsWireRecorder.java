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

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Records, for the members in debug mode linked to one bot, the BGS exchanges of its session that name them: one
 * entry per call and its reply (or server call and our ack), the header and body as JSON next to the raw bytes.
 * Whom a frame names is read from its decoded body (exact values) and, when a body could not be decoded with its
 * schema, searched in its raw bytes.
 *
 * <p>Only the friends and presence services are recorded: everything else (login, connection, account, game account
 * selection, own presence, unknown services) can carry secrets and is dropped before being decoded.
 */
@Slf4j
public final class BgsWireRecorder implements WireTap {

    /** The HTTP-like header holding the BGS header as JSON. */
    static final String HEADER = "Bgs-Header";

    private static final List<String> RECORDED_SERVICES = List.of("friends.", "presence.");
    /** Raw and unknown calls are only known by their hash: those of the recorded services the table knows. */
    private static final Set<Integer> RECORDED_HASHES = recordedHashes();

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
            if (exchange.requestHeader() == null || !recorded(exchange)) {
                return;
            }
            ServiceMethod<?, ?> method = exchange.method();
            Body request = body(exchange.requestBody(), method == null ? null : method.requestParser());
            Body reply = body(exchange.replyBody(), method == null ? null : method.responseParser());
            Predicate<String> concerns = concerns(request).or(concerns(reply));
            debug.exchange(serviceAccountId, concerns, exchange.initiatedLocally() ? Direction.OUT : Direction.IN,
                    () -> raw(exchange, request, reply), operation(exchange));
        } catch (RuntimeException e) {
            log.warn("Could not record a BGS exchange of {} for the members in debug mode: {}", serviceAccountId,
                    e.toString());
        }
    }

    private static boolean recorded(WireExchange exchange) {
        ServiceMethod<?, ?> method = exchange.method();
        if (method != null) {
            return RECORDED_SERVICES.stream().anyMatch(method.service()::startsWith);
        }
        return RECORDED_HASHES.contains(exchange.requestHeader().getServiceHash());
    }

    /** The hashes of the recorded services among the methods {@link BgsServices} declares. */
    private static Set<Integer> recordedHashes() {
        return Arrays.stream(BgsServices.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers())
                        && ServiceMethod.class.isAssignableFrom(field.getType()))
                .map(field -> {
                    try {
                        return (ServiceMethod<?, ?>) field.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(method -> RECORDED_SERVICES.stream().anyMatch(method.service()::startsWith))
                .map(ServiceMethod::serviceHash)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Who a body names: exact leaf values when its schema decoded it, else its raw bytes (and the leaves decoded
     * without schema).
     */
    private static Predicate<String> concerns(Body body) {
        Predicate<String> leaves = WireKeys.concerns(WireKeys.of(body.json()));
        return body.typed() ? leaves : leaves.or(WireKeys.rawConcerns(body.raw()));
    }

    private static String operation(WireExchange exchange) {
        ServiceMethod<?, ?> method = exchange.method();
        if (method != null) {
            return "bgs." + method.service() + "." + method.methodId();
        }
        Header header = exchange.requestHeader();
        return "bgs." + String.format("0x%08x", header.getServiceHash()) + "." + header.getMethodId();
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
