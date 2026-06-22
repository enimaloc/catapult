package fr.enimaloc.catapult.web.ws.codec.msg;

import java.util.List;
import java.util.UUID;

public record AuthOkMessage(UUID userId, List<String> roles) implements WsOutgoing {
}
