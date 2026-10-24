package fr.enimaloc.catapult.service.http;

import fr.enimaloc.catapult.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * catapult-api's problem answer to a call: its {@code code} (null when this build does not know it),
 * its English {@code detail} (never shown as is: catapult-web maps the code to its own texts) and the
 * whole problem, for its extensions. Read from the body whatever its content type: a problem may come
 * back as {@code application/json} when the request asked for JSON.
 */
public record ApiError(ErrorCode code, String detail, JsonNode problem) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The problem's {@code name} extension read as {@code type}; null when absent or not a {@code type}. */
    public <T> T property(String name, Class<T> type) {
        JsonNode value = problem.get(name);
        if (value == null || value.isNull()) return null;
        try {
            return JSON.treeToValue(value, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The problem {@code body} holds: a JSON object with a textual {@code code}; null for anything else. */
    static ApiError parse(byte[] body) {
        if (body == null || body.length == 0) return null;
        JsonNode problem;
        try {
            problem = JSON.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
        if (problem == null || !problem.isObject()) return null;
        JsonNode code = problem.get(ErrorCode.PROPERTY);
        if (code == null || !code.isString()) return null;
        JsonNode detail = problem.get("detail");
        return new ApiError(ErrorCode.fromCode(code.stringValue()).orElse(null),
                detail != null && detail.isString() ? detail.stringValue() : null, problem);
    }
}
