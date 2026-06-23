package fr.enimaloc.catapult.web.ws;

import fr.enimaloc.catapult.web.ws.codec.JsonMessageCodec;
import fr.enimaloc.catapult.web.ws.codec.msg.AuthMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.CommandMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.ErrorMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.EventMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.PongMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.RequestMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.ResponseMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.SubDeniedMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.SubOkMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.SubscribeMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.UnsubscribeMessage;
import fr.enimaloc.catapult.web.ws.auth.WsAuthContext;
import fr.enimaloc.catapult.web.ws.auth.WsTicketStore;
import fr.enimaloc.catapult.web.ws.codec.msg.AuthOkMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.WsIncoming;
import fr.enimaloc.catapult.web.ws.codec.msg.WsOutgoing;
import fr.enimaloc.catapult.web.ws.codec.msg.PingMessage;
import fr.enimaloc.catapult.web.ws.dispatch.ChannelResolver;
import fr.enimaloc.catapult.web.ws.dispatch.HtmxWsDispatcher;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import fr.enimaloc.catapult.web.ws.dispatch.WsRequestDispatcher;
import fr.enimaloc.catapult.web.ws.ratelimit.WsRateLimiter;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsHub extends TextWebSocketHandler {

    private final WsSessionRegistry registry;
    private final ChannelResolver channelResolver;
    private final JsonMessageCodec codec;
    private final WsTicketStore ticketStore;
    private final WsRequestDispatcher dispatcher;
    private final WsRateLimiter rateLimiter;
    private final HtmxWsDispatcher htmxDispatcher;

    @Override
    public void afterConnectionEstablished(WebSocketSession springSession) {
        registry.add(new WsSession(springSession));
        log.debug("ws connected: id={} total={}", springSession.getId(), registry.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession springSession, TextMessage message) {
        WsSession session = registry.get(springSession.getId());
        if (session == null) {
            log.warn("ws message from unregistered session id={}", springSession.getId());
            return;
        }
        WsIncoming msg;
        try {
            msg = codec.decodeIncoming(message.getPayload());
        } catch (Exception e) {
            send(session, new ErrorMessage("INVALID_FRAME", "JSON parse error"));
            return;
        }
        dispatch(session, msg);
    }

    private void dispatch(WsSession session, WsIncoming msg) {
        switch (msg) {
            case PongMessage ignored -> {
                /* no-op, client liveness signal */
            }
            case SubscribeMessage s -> handleSubscribe(session, s);
            case UnsubscribeMessage u -> handleUnsubscribe(session, u);
            case AuthMessage a -> handleAuth(session, a);
            case RequestMessage r -> handleRequest(session, r);
            case CommandMessage c -> handleCommand(session, c);
        }
    }

    private void handleRequest(WsSession session, RequestMessage msg) {
        WsAuthContext.set(session.jwt());
        try {
            if (HtmxWsDispatcher.ACTION.equals(msg.action())) {
                if (!rateLimiter.tryAcquire(session.id(), WsRateLimiter.BUCKET_GLOBAL)
                        || !rateLimiter.tryAcquire(session.id(), WsRateLimiter.BUCKET_HTMX)) {
                    send(session, ResponseMessage.error(msg.id(), "RATE_LIMITED", "HTMX rate limit exceeded"));
                    return;
                }
                send(session, htmxDispatcher.dispatch(msg.id(), session, msg.params()));
                return;
            }
            Object result = dispatcher.dispatch(session, msg.action(), msg.params());
            send(session, ResponseMessage.ok(msg.id(), result));
        } catch (WsBusinessException ex) {
            send(session, ResponseMessage.error(msg.id(), ex.code(), ex.getMessage()));
        } catch (Exception ex) {
            log.warn("request {} action={} failed: {}", msg.id(), msg.action(), ex.toString());
            send(session, ResponseMessage.error(msg.id(), "INTERNAL_ERROR", "Server error"));
        } finally {
            WsAuthContext.clear();
        }
    }

    private void handleCommand(WsSession session, CommandMessage msg) {
        WsAuthContext.set(session.jwt());
        try {
            dispatcher.dispatch(session, msg.action(), msg.params());
        } catch (WsBusinessException ex) {
            log.debug("command action={} rejected: {} {}", msg.action(), ex.code(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("command action={} failed: {}", msg.action(), ex.toString());
        } finally {
            WsAuthContext.clear();
        }
    }

    private void handleAuth(WsSession session, AuthMessage msg) {
        var snapshot = ticketStore.consume(msg.token());
        if (snapshot.isEmpty()) {
            send(session, new ErrorMessage("INVALID_TICKET", "Auth ticket invalid or already consumed"));
            try {
                session.springSession().close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.debug("close after invalid ticket failed for {}: {}", session.id(), e.getMessage());
            }
            return;
        }
        session.authenticate(snapshot.get().userId(), snapshot.get().roles(), snapshot.get().jwt());
        send(session, new AuthOkMessage(snapshot.get().userId(), List.copyOf(snapshot.get().roles())));
    }

    private void handleSubscribe(WsSession session, SubscribeMessage msg) {
        var resolved = channelResolver.resolvePublicToInternal(msg.channel(), session);
        if (resolved.isEmpty()) {
            send(session, new SubDeniedMessage(msg.channel(), "FORBIDDEN_OR_UNKNOWN"));
            return;
        }
        registry.subscribe(session.id(), resolved.get());
        send(session, new SubOkMessage(msg.channel()));
    }

    private void handleUnsubscribe(WsSession session, UnsubscribeMessage msg) {
        var resolved = channelResolver.resolvePublicToInternal(msg.channel(), session);
        resolved.ifPresent(internal -> registry.unsubscribe(session.id(), internal));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession springSession, CloseStatus status) {
        registry.remove(springSession.getId());
        rateLimiter.cleanup(springSession.getId());
        log.debug("ws closed: id={} status={} total={}", springSession.getId(), status, registry.size());
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        var ping = new PingMessage(System.currentTimeMillis());
        for (WsSession s : registry.allSessions()) {
            send(s, ping);
        }
    }

    public void broadcast(String internalChannel, EventMessage event) {
        for (WsSession s : registry.subscribersOf(internalChannel)) {
            send(s, event);
        }
    }

    private void send(WsSession session, WsOutgoing msg) {
        try {
            session.springSession().sendMessage(new TextMessage(codec.encode(msg)));
        } catch (IOException ex) {
            log.debug("send failed, removing session {}: {}", session.id(), ex.getMessage());
            registry.remove(session.id());
        }
    }
}
