package fr.enimaloc.catapult.web.ws.codec.msg;

public record AuthMessage(String token) implements WsIncoming {
}
