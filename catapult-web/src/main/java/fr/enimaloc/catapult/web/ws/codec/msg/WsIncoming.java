package fr.enimaloc.catapult.web.ws.codec.msg;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AuthMessage.class,        name = "auth"),
        @JsonSubTypes.Type(value = SubscribeMessage.class,   name = "subscribe"),
        @JsonSubTypes.Type(value = UnsubscribeMessage.class, name = "unsubscribe"),
        @JsonSubTypes.Type(value = RequestMessage.class,     name = "request"),
        @JsonSubTypes.Type(value = CommandMessage.class,     name = "command"),
        @JsonSubTypes.Type(value = PongMessage.class,        name = "pong")
})
public sealed interface WsIncoming permits AuthMessage, SubscribeMessage, UnsubscribeMessage,
        RequestMessage, CommandMessage, PongMessage {
}
