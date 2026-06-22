package fr.enimaloc.catapult.web.ws.codec.msg;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseMessage(
        String id,
        boolean ok,
        Object result,
        ErrorInfo error
) implements WsOutgoing {

    public record ErrorInfo(String code, String message) {
    }

    public static ResponseMessage ok(String id, Object result) {
        return new ResponseMessage(id, true, result, null);
    }

    public static ResponseMessage error(String id, String code, String message) {
        return new ResponseMessage(id, false, null, new ErrorInfo(code, message));
    }
}
