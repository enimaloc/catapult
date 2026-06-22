package fr.enimaloc.catapult.web.ws.codec.msg;

public record CommandMessage(String action, Object params) implements WsIncoming {
}
