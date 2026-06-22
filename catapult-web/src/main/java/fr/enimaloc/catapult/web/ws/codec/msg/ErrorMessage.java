package fr.enimaloc.catapult.web.ws.codec.msg;

public record ErrorMessage(String code, String message) implements WsOutgoing {
}
