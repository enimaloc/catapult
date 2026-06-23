package fr.enimaloc.catapult.web.ws.codec.msg;

public record EventMessage(String channel, String name, Object data) implements WsOutgoing {
}
