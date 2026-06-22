package fr.enimaloc.catapult.web.ws.codec.msg;

public record UnsubscribeMessage(String channel) implements WsIncoming {
}
