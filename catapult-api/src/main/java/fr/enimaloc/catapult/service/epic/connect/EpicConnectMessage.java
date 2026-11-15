package fr.enimaloc.catapult.service.epic.connect;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * The JSON body of an Epic Connect {@code MESSAGE} frame: its type (for instance {@code presence.v1.UPDATE}), the
 * connection id and the type-specific payload (a missing node when the message carries none).
 */
public record EpicConnectMessage(String type, String connectionId, JsonNode payload) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Empty unless the frame is a {@code MESSAGE} whose body is a JSON object. */
    public static Optional<EpicConnectMessage> from(StompFrames.Frame frame) {
        if (!"MESSAGE".equals(frame.command())) {
            return Optional.empty();
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(frame.body());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (body == null || !body.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new EpicConnectMessage(
                body.path("type").asString(""), body.path("connectionId").asString(""), body.path("payload")));
    }
}
