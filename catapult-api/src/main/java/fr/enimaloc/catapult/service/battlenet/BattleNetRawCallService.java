package fr.enimaloc.catapult.service.battlenet;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.bgs.proto.BgsCatalog;
import fr.enimaloc.catapult.bgs.proto.BgsCatalog.MethodInfo;
import fr.enimaloc.catapult.common.dto.admin.BgsCallRequest;
import fr.enimaloc.catapult.common.dto.admin.BgsCallResult;
import fr.enimaloc.catapult.common.dto.admin.BgsMethodDto;
import fr.enimaloc.catapult.common.dto.admin.BgsPushDto;
import fr.enimaloc.catapult.common.dto.admin.BgsServiceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * An admin's free BGS call on a bot's ready session, to explore the protocol (first use: finding the presence field
 * of "appear offline"). The call is a known bgs.desc method with a protobuf text body, or raw (hash, method id, hex
 * body). It answers the BGS status, the reply and the pushes the server sent during {@link #WINDOW} after the call.
 * One call at a time per bot. Bodies are never logged: they can name any friend of the bot.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetRawCallService {

    static final Duration WINDOW = Duration.ofSeconds(5);
    static final Duration REPLY_TIMEOUT = Duration.ofSeconds(10);

    private static final HexFormat HEX = HexFormat.of();

    private record Target(int serviceHash, int methodId, ByteString body, boolean withCiid, MethodInfo method) {
    }

    private final BattleNetSessionManager sessions;
    private final Clock clock;
    private final Consumer<Duration> sleeper;
    private final Duration replyTimeout;

    @Autowired
    public BattleNetRawCallService(BattleNetSessionManager sessions) {
        this(sessions, Clock.systemUTC(), BattleNetRawCallService::sleep);
    }

    BattleNetRawCallService(BattleNetSessionManager sessions, Clock clock, Consumer<Duration> sleeper) {
        this(sessions, clock, sleeper, REPLY_TIMEOUT);
    }

    BattleNetRawCallService(BattleNetSessionManager sessions, Clock clock, Consumer<Duration> sleeper,
                            Duration replyTimeout) {
        this.sessions = sessions;
        this.clock = clock;
        this.sleeper = sleeper;
        this.replyTimeout = replyTimeout;
    }

    /** Every bgs.desc service and its methods, sorted by name then id. */
    public List<BgsServiceDto> catalog() {
        return BgsCatalog.instance().services().stream()
                .map(s -> new BgsServiceDto(s.fullName(), hash(s.hash()), s.methods().stream()
                        .map(m -> new BgsMethodDto(m.name(), m.id(), m.input().getFullName(),
                                m.output().getFullName(), m.noResponse()))
                        .toList()))
                .toList();
    }

    /**
     * @throws ResponseStatusException 400 for an invalid request (unknown method, text or hex that does not parse),
     *                                 409 when this instance has no ready session for the bot or a call already runs
     */
    public BgsCallResult call(UUID accountId, BgsCallRequest request, String admin) {
        Target target = target(request);
        PushCapture capture = sessions.captureOf(accountId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "No ready BGS session for this bot on this instance"));
        if (!capture.arm(WINDOW)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A call is already running on this bot");
        }
        try {
            Instant start = clock.instant();
            boolean expectReply = target.method() == null || !target.method().noResponse();
            Integer status = null;
            String error = null;
            ByteString reply = ByteString.EMPTY;
            try {
                reply = sessions.withClient(accountId, client -> client.rawCall(target.serviceHash(),
                                target.methodId(), target.body(), target.withCiid(), expectReply))
                        .get(replyTimeout.toMillis(), TimeUnit.MILLISECONDS);
                status = expectReply ? 0 : null;
            } catch (ExecutionException | CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof BgsRpcException rpc) {
                    status = rpc.status();
                } else if (cause instanceof IllegalStateException) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, cause.getMessage());
                } else {
                    error = cause.toString();
                }
            } catch (TimeoutException e) {
                error = "timeout";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "interrupted";
            }
            long durationMs = Duration.between(start, clock.instant()).toMillis();
            log.info("Admin {} sent BGS call {}.{} to Battle.net bot {}: status {}, error {}, {} ms", admin,
                    hash(target.serviceHash()), target.methodId(), accountId, status, error, durationMs);
            Duration left = Duration.between(clock.instant(), start.plus(WINDOW));
            if (left.isPositive()) {
                sleeper.accept(left);
            }
            String replyText = target.method() == null || !expectReply ? null
                    : BgsCatalog.decode(target.method().output(), reply).orElse(null);
            return new BgsCallResult(status, durationMs, replyText, HEX.formatHex(reply.toByteArray()),
                    capture.release().stream().map(BattleNetRawCallService::push).toList(), error);
        } finally {
            capture.release();
        }
    }

    private static Target target(BgsCallRequest request) {
        if (request.service() != null) {
            MethodInfo method = BgsCatalog.instance().find(request.service(), request.method())
                    .orElseThrow(() -> badRequest("Unknown BGS method " + request.service() + "." + request.method()));
            ByteString body;
            try {
                body = BgsCatalog.encode(method.input(), request.text());
            } catch (TextFormat.ParseException e) {
                throw badRequest("Invalid body: " + e.getMessage());
            }
            boolean ciid = request.withCiid() != null ? request.withCiid() : defaultCiid(method.service());
            return new Target(method.serviceHash(), method.id(), body, ciid, method);
        }
        if (request.serviceHash() == null || request.methodId() == null) {
            throw badRequest("A raw call needs a service hash and a method id");
        }
        int hash;
        try {
            hash = (int) Long.parseLong(strip(request.serviceHash()), 16);
        } catch (NumberFormatException e) {
            throw badRequest("Invalid service hash: " + request.serviceHash());
        }
        ByteString body;
        try {
            body = ByteString.copyFrom(HEX.parseHex(strip(request.hex() == null ? "" : request.hex())));
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid hex body: " + e.getMessage());
        }
        Optional<MethodInfo> method = BgsCatalog.instance().find(hash, request.methodId());
        boolean ciid = request.withCiid() != null ? request.withCiid()
                : method.map(m -> defaultCiid(m.service())).orElse(false);
        return new Target(hash, request.methodId(), body, ciid, method.orElse(null));
    }

    private static BgsPushDto push(PushCapture.CapturedPush push) {
        Optional<MethodInfo> method = BgsCatalog.instance().find(push.serviceHash(), push.methodId());
        return new BgsPushDto(push.at(),
                method.map(MethodInfo::service).orElse(hash(push.serviceHash())),
                method.map(MethodInfo::name).orElse(String.valueOf(push.methodId())),
                method.flatMap(m -> BgsCatalog.decode(m.input(), push.body())).orElse(null),
                HEX.formatHex(push.body().toByteArray()));
    }

    /** Same rule as the session: friends and presence calls carry the ciid. */
    private static boolean defaultCiid(String service) {
        return service.contains("friends") || service.contains("presence");
    }

    /** Drops whitespace and a leading {@code 0x}. */
    private static String strip(String hex) {
        String s = hex.replaceAll("\\s+", "");
        return s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
    }

    private static String hash(int hash) {
        return String.format("0x%08X", hash);
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
