package fr.enimaloc.catapult.web.ws.codec.msg;

public record RequestMessage(String id, String action, Object params) implements WsIncoming {
}
