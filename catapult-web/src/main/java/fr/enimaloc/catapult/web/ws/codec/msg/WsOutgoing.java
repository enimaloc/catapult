package fr.enimaloc.catapult.web.ws.codec.msg;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AuthOkMessage.class,    name = "auth.ok"),
        @JsonSubTypes.Type(value = SubOkMessage.class,     name = "sub.ok"),
        @JsonSubTypes.Type(value = SubDeniedMessage.class, name = "sub.denied"),
        @JsonSubTypes.Type(value = ResponseMessage.class,  name = "response"),
        @JsonSubTypes.Type(value = EventMessage.class,     name = "event"),
        @JsonSubTypes.Type(value = PingMessage.class,      name = "ping"),
        @JsonSubTypes.Type(value = ErrorMessage.class,     name = "error")
})
public sealed interface WsOutgoing permits AuthOkMessage, SubOkMessage, SubDeniedMessage,
        ResponseMessage, EventMessage, PingMessage, ErrorMessage {
}
