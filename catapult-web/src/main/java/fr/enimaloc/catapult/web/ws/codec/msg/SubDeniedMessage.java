package fr.enimaloc.catapult.web.ws.codec.msg;

public record SubDeniedMessage(String channel, String reason) implements WsOutgoing {
}
