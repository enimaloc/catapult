package fr.enimaloc.catapult.web.ws.codec.msg;

public record SubscribeMessage(String channel) implements WsIncoming {
}
