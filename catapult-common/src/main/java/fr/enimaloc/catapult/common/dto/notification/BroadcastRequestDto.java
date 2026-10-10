package fr.enimaloc.catapult.common.dto.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sealed polymorphic request body for {@code POST /api/admin/broadcast}.
 *
 * <p>Jackson resolves the concrete subtype from the {@code name} discriminator —
 * this matches the spec §8 «names whitelist», each name has a strict payload shape.
 * Validation annotations on the record components are enforced by {@code @Valid}
 * on the controller method.</p>
 *
 * <p>The {@code maintenance.imminent} name is intentionally NOT exposed here :
 * it is reserved for the lifecycle shutdown hook and must never be triggered
 * from the admin endpoint. {@code BroadcastValidator} rejects it explicitly if
 * a caller crafts the discriminator manually.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "name")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BroadcastRequestDto.MaintenanceScheduled.class, name = "maintenance.scheduled"),
        @JsonSubTypes.Type(value = BroadcastRequestDto.MaintenanceCancelled.class, name = "maintenance.cancelled"),
        @JsonSubTypes.Type(value = BroadcastRequestDto.VersionDeployed.class,      name = "version.deployed"),
        @JsonSubTypes.Type(value = BroadcastRequestDto.AlertInfo.class,            name = "alert.info"),
        @JsonSubTypes.Type(value = BroadcastRequestDto.AlertWarning.class,         name = "alert.warning")
})
public sealed interface BroadcastRequestDto
        permits BroadcastRequestDto.MaintenanceScheduled,
                BroadcastRequestDto.MaintenanceCancelled,
                BroadcastRequestDto.VersionDeployed,
                BroadcastRequestDto.AlertInfo,
                BroadcastRequestDto.AlertWarning {

    /**
     * Redis channel all current broadcast names publish on. Duplicated from
     * {@code RedisEventPublisher.CHANNEL_GLOBAL} (catapult-api) rather than
     * imported — this module must not depend back on catapult-api.
     */
    String CHANNEL_GLOBAL = "catapult:events:global";

    /** Discriminator name as sent over the wire (matches the {@code @JsonSubTypes} entries). */
    @JsonIgnore
    String name();

    /**
     * Returns the Redis channel this broadcast must be published on.
     * All current names map to the global channel; admin-only broadcasts are
     * not yet exposed via this DTO surface.
     */
    @JsonIgnore
    default String redisChannel() {
        return CHANNEL_GLOBAL;
    }

    /** WS-side channel a client subscribes to. Used for audit logging and routing. */
    @JsonIgnore
    default String wsChannel() {
        return "events.global";
    }

    /**
     * The payload object Jackson will serialize as the {@code data} field of the
     * published event envelope. Returns a plain {@link Map} so the discriminator
     * is not redundantly included in the broadcast payload — the envelope already
     * carries the {@code name} at the top level.
     */
    @JsonIgnore
    Map<String, Object> data();

    /** Ordered {@code data()} map from alternating keys and values; values may be null. */
    private static Map<String, Object> orderedData(Object... keysAndValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return data;
    }

    // ── Subtypes ─────────────────────────────────────────────────────────────

    record MaintenanceScheduled(
            @NotNull Instant startsAt,
            @Min(1) int durationMinutes,
            @NotBlank @Size(max = 500) String message
    ) implements BroadcastRequestDto {
        @Override public String name() { return "maintenance.scheduled"; }
        @Override public Map<String, Object> data() {
            return BroadcastRequestDto.orderedData("startsAt", startsAt, "durationMinutes", durationMinutes, "message", message);
        }
    }

    record MaintenanceCancelled(
            @NotNull Instant originalStartsAt,
            @NotBlank @Size(max = 500) String reason
    ) implements BroadcastRequestDto {
        @Override public String name() { return "maintenance.cancelled"; }
        @Override public Map<String, Object> data() {
            return BroadcastRequestDto.orderedData("originalStartsAt", originalStartsAt, "reason", reason);
        }
    }

    record VersionDeployed(
            @NotBlank @Size(max = 64) String version,
            boolean promptReload
    ) implements BroadcastRequestDto {
        @Override public String name() { return "version.deployed"; }
        @Override public Map<String, Object> data() {
            return BroadcastRequestDto.orderedData("version", version, "promptReload", promptReload);
        }
    }

    record AlertInfo(
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 2000) String body,
            @Min(0) int ttlSeconds
    ) implements BroadcastRequestDto {
        @Override public String name() { return "alert.info"; }
        @Override public Map<String, Object> data() {
            return BroadcastRequestDto.orderedData("title", title, "body", body, "ttlSeconds", ttlSeconds);
        }
    }

    record AlertWarning(
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 2000) String body,
            @Min(0) int ttlSeconds
    ) implements BroadcastRequestDto {
        @Override public String name() { return "alert.warning"; }
        @Override public Map<String, Object> data() {
            return BroadcastRequestDto.orderedData("title", title, "body", body, "ttlSeconds", ttlSeconds);
        }
    }
}
