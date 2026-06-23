package fr.enimaloc.catapult.web.ws.codec.msg;

import java.util.Map;

/**
 * For {@code action="htmx"} the envelope carries top-level {@code method},
 * {@code path}, {@code headers}, {@code csrfToken} per spec §6 — captured
 * here so Jackson does not silently drop them as unknown properties.
 */
public record RequestMessage(
        String id,
        String action,
        String method,
        String path,
        Map<String, String> headers,
        Object params,
        String csrfToken
) implements WsIncoming {

    public RequestMessage(String id, String action, Object params) {
        this(id, action, null, null, null, params, null);
    }
}
