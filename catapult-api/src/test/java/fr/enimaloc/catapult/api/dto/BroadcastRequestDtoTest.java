package fr.enimaloc.catapult.api.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastRequestDtoTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void roundtrip_maintenance_scheduled() {
        String body = """
                {"name":"maintenance.scheduled",
                 "startsAt":"2026-06-22T20:00:00Z",
                 "durationMinutes":15,
                 "message":"deploying"}""";

        BroadcastRequestDto dto = json.readValue(body, BroadcastRequestDto.class);

        assertThat(dto).isInstanceOf(BroadcastRequestDto.MaintenanceScheduled.class);
        var ms = (BroadcastRequestDto.MaintenanceScheduled) dto;
        assertThat(ms.startsAt()).isEqualTo(Instant.parse("2026-06-22T20:00:00Z"));
        assertThat(ms.durationMinutes()).isEqualTo(15);
        assertThat(ms.message()).isEqualTo("deploying");
        assertThat(dto.name()).isEqualTo("maintenance.scheduled");
        assertThat(dto.wsChannel()).isEqualTo("events.global");
    }

    @Test
    void roundtrip_maintenance_cancelled() {
        String body = """
                {"name":"maintenance.cancelled",
                 "originalStartsAt":"2026-06-22T20:00:00Z",
                 "reason":"oups"}""";

        BroadcastRequestDto dto = json.readValue(body, BroadcastRequestDto.class);
        assertThat(dto).isInstanceOf(BroadcastRequestDto.MaintenanceCancelled.class);
        var mc = (BroadcastRequestDto.MaintenanceCancelled) dto;
        assertThat(mc.reason()).isEqualTo("oups");
        assertThat(dto.name()).isEqualTo("maintenance.cancelled");
    }

    @Test
    void roundtrip_version_deployed() {
        String body = """
                {"name":"version.deployed","version":"0.8.0","promptReload":true}""";
        BroadcastRequestDto dto = json.readValue(body, BroadcastRequestDto.class);
        assertThat(dto).isInstanceOf(BroadcastRequestDto.VersionDeployed.class);
        var vd = (BroadcastRequestDto.VersionDeployed) dto;
        assertThat(vd.version()).isEqualTo("0.8.0");
        assertThat(vd.promptReload()).isTrue();
        assertThat(vd.data()).containsEntry("version", "0.8.0").containsEntry("promptReload", true);
    }

    @Test
    void roundtrip_alert_info() {
        String body = """
                {"name":"alert.info","title":"hello","body":"world","ttlSeconds":60}""";
        BroadcastRequestDto dto = json.readValue(body, BroadcastRequestDto.class);
        assertThat(dto).isInstanceOf(BroadcastRequestDto.AlertInfo.class);
        var info = (BroadcastRequestDto.AlertInfo) dto;
        assertThat(info.title()).isEqualTo("hello");
        assertThat(info.body()).isEqualTo("world");
        assertThat(info.ttlSeconds()).isEqualTo(60);
        assertThat(dto.name()).isEqualTo("alert.info");
    }

    @Test
    void roundtrip_alert_warning() {
        String body = """
                {"name":"alert.warning","title":"high cpu","body":"investigate","ttlSeconds":300}""";
        BroadcastRequestDto dto = json.readValue(body, BroadcastRequestDto.class);
        assertThat(dto).isInstanceOf(BroadcastRequestDto.AlertWarning.class);
        assertThat(dto.name()).isEqualTo("alert.warning");
    }

    @Test
    void data_map_omits_discriminator_to_avoid_duplication_in_envelope() {
        BroadcastRequestDto dto = new BroadcastRequestDto.MaintenanceScheduled(
                Instant.parse("2026-06-22T20:00:00Z"), 15, "deploying");

        var data = dto.data();

        assertThat(data).doesNotContainKey("name");
        assertThat(data).containsOnlyKeys("startsAt", "durationMinutes", "message");
    }
}
