package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.api.dto.BroadcastRequestDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * Server-side guard for admin broadcasts (spec §8 + §10).
 *
 * <p>Enforces three invariants before any payload is published on Redis :</p>
 * <ul>
 *   <li>the {@code name} discriminator is part of the documented whitelist
 *       (the lifecycle-only {@code maintenance.imminent} is rejected as well —
 *       it must only be emitted by the in-process shutdown hook, never by an
 *       external caller);</li>
 *   <li>the serialized data payload stays below {@value #MAX_DATA_BYTES} bytes —
 *       broadcasts are intentionally lightweight, this prevents accidentally
 *       blasting a 1 MB blob across every connected client;</li>
 *   <li>the resolved Redis channel is either the global one or the admin one
 *       — those are the only two outgoing fan-outs sanctioned by spec.</li>
 * </ul>
 */
@Component
public class BroadcastValidator {

    /** 8 KB — generous for textual alerts, tight enough to keep fanout cheap. */
    public static final int MAX_DATA_BYTES = 8 * 1024;

    /**
     * Names accepted on the admin endpoint. {@code maintenance.imminent} is
     * intentionally absent: it is reserved for the Spring lifecycle shutdown
     * hook in catapult-web.
     */
    public static final Set<String> ALLOWED_NAMES = Set.of(
            "maintenance.scheduled",
            "maintenance.cancelled",
            "version.deployed",
            "alert.info",
            "alert.warning"
    );

    /** Names accepted nowhere on the public surface — currently only the lifecycle-only one. */
    public static final Set<String> RESERVED_NAMES = Set.of(
            "maintenance.imminent"
    );

    public static final Set<String> ALLOWED_REDIS_CHANNELS = Set.of(
            RedisEventPublisher.CHANNEL_GLOBAL,
            RedisEventPublisher.CHANNEL_ADMIN
    );

    private final ObjectMapper jackson;

    public BroadcastValidator() {
        this(JsonMapper.builder().build());
    }

    @Autowired(required = false)
    public BroadcastValidator(ObjectMapper jackson) {
        this.jackson = jackson != null ? jackson : JsonMapper.builder().build();
    }

    /**
     * Validates the request. Throws {@link IllegalArgumentException} with a
     * specific message on any rule violation so the controller can map it to
     * {@code 400 Bad Request}.
     */
    public void validate(BroadcastRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("broadcast body is required");
        }
        validateName(dto.name());
        if (!ALLOWED_REDIS_CHANNELS.contains(dto.redisChannel())) {
            throw new IllegalArgumentException(
                    "channel '" + dto.redisChannel() + "' is not in the broadcast whitelist");
        }
        int size = serializedDataSize(dto.data());
        if (size > MAX_DATA_BYTES) {
            throw new IllegalArgumentException(
                    "data payload too large: " + size + " bytes (max " + MAX_DATA_BYTES + ")");
        }
    }

    /**
     * Standalone name check — used both by {@link #validate(BroadcastRequestDto)}
     * and by callers that want to enforce the whitelist before construction
     * (e.g. a raw-string admin tool, or a defence-in-depth check after
     * Jackson deserialization).
     */
    public void validateName(String name) {
        if (RESERVED_NAMES.contains(name)) {
            throw new IllegalArgumentException(
                    "name '" + name + "' is reserved for the lifecycle shutdown hook");
        }
        if (!ALLOWED_NAMES.contains(name)) {
            throw new IllegalArgumentException("name '" + name + "' is not in the broadcast whitelist");
        }
    }

    private int serializedDataSize(Object data) {
        try {
            return jackson.writeValueAsBytes(data).length;
        } catch (Exception e) {
            // If we can't serialize the payload at all, treat it as invalid.
            throw new IllegalArgumentException("data payload is not JSON-serializable: " + e.getMessage());
        }
    }
}
