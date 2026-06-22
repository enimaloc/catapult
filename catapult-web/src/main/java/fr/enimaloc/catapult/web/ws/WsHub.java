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
import fr.enimaloc.catapult.web.ws.codec.msg.WsIncoming;
import fr.enimaloc.catapult.web.ws.codec.msg.WsOutgoing;
import fr.enimaloc.catapult.web.ws.codec.msg.PingMessage;
import fr.enimaloc.catapult.web.ws.dispatch.ChannelResolver;
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
            case AuthMessage ignored -> send(session, new ErrorMessage("NOT_YET_IMPLEMENTED", "auth handler in phase 3"));
            case RequestMessage r -> send(session, ResponseMessage.error(r.id(), "UNKNOWN_ACTION", "No handlers registered"));
            case CommandMessage ignored -> send(session, new ErrorMessage("UNKNOWN_ACTION", "No handlers registered"));
        }
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
