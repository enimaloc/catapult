package fr.enimaloc.catapult.web.ws.codec.msg;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * RPC response frame. Carries either a generic {@code result} (search/admin
 * actions) or an inlined HTMX payload ({@code status}/{@code html}/etc.) at
 * the top level — the spec requires HTMX fields not to be nested under
 * {@code result}. {@link com.fasterxml.jackson.annotation.JsonInclude.Include#NON_NULL}
 * keeps non-htmx responses identical to before.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseMessage(
        String id,
        boolean ok,
        Object result,
        ErrorInfo error,
        Integer status,
        String target,
        String swap,
        String html,
        List<OobSwap> oob,
        Map<String, Object> triggers
) implements WsOutgoing {

    public record ErrorInfo(String code, String message) {
    }

    public record OobSwap(String target, String swap, String html) {
    }

    public static ResponseMessage ok(String id, Object result) {
        return new ResponseMessage(id, true, result, null, null, null, null, null, null, null);
    }

    public static ResponseMessage error(String id, String code, String message) {
        return new ResponseMessage(id, false, null, new ErrorInfo(code, message),
                null, null, null, null, null, null);
    }

    /** HTMX response with inlined fields at the top level (spec §6). */
    public static ResponseMessage htmx(String id, int status, String target, String swap,
                                       String html, List<OobSwap> oob, Map<String, Object> triggers) {
        return new ResponseMessage(id, true, null, null, status, target, swap, html, oob, triggers);
    }
}
