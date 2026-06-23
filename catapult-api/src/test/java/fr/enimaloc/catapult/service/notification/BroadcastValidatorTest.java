package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.api.dto.BroadcastRequestDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BroadcastValidatorTest {

    private final BroadcastValidator validator = new BroadcastValidator();

    @Test
    void valid_maintenance_scheduled_passes() {
        var dto = new BroadcastRequestDto.MaintenanceScheduled(
                Instant.parse("2026-06-22T20:00:00Z"), 15, "deploying v0.8.0");
        validator.validate(dto);
    }

    @Test
    void valid_alert_info_passes() {
        var dto = new BroadcastRequestDto.AlertInfo("title", "body", 60);
        validator.validate(dto);
    }

    @Test
    void valid_version_deployed_passes() {
        var dto = new BroadcastRequestDto.VersionDeployed("0.8.0", true);
        validator.validate(dto);
    }

    @Test
    void null_dto_rejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void name_whitelist_check_rejects_reserved_lifecycle_name() {
        // Belt-and-suspenders: Jackson already prevents deserialising "maintenance.imminent"
        // (it is not in @JsonSubTypes), but the validator double-checks via the runtime name.
        assertThatThrownBy(() -> validator.validateName("maintenance.imminent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void name_whitelist_check_rejects_unknown_name() {
        assertThatThrownBy(() -> validator.validateName("party.time"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitelist");
    }

    @Test
    void name_whitelist_check_accepts_known_names() {
        for (String name : BroadcastValidator.ALLOWED_NAMES) {
            validator.validateName(name);
        }
    }

    @Test
    void oversized_data_rejected() {
        // The AlertInfo record's @Size(max=2000) on `body` is a Jakarta validation
        // annotation — it is NOT enforced at runtime construction, only by an
        // explicit Validator (which the controller runs via @Valid). The
        // BroadcastValidator's own size check is independent and operates on the
        // *serialized* Map<String,Object> returned by data().
        String big = "x".repeat(9 * 1024);
        var dto = new BroadcastRequestDto.AlertInfo("t", big, 1);

        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }
}
