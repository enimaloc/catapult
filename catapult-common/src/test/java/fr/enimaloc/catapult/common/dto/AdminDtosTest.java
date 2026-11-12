package fr.enimaloc.catapult.common.dto;

import fr.enimaloc.catapult.common.dto.admin.DtddTopicSeverityDto;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicsBody;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicsCacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.TwRebuildStatusDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin DTOs' shorter constructors and their read of payloads from before a field. */
class AdminDtosTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aSeverity_namesItsLevels_andTheReviewersOne() {
        DtddTopicSeverityDto severity = new DtddTopicSeverityDto(List.of("Implied", "Depicted", "Excessive"),
                List.of(2, 9, 1), List.of(17, 75, 8), 12, 2);

        assertThat(severity.verifiedLevel()).isEqualTo("Depicted");
        assertThat(severity.level(1)).isEqualTo("Implied");
        assertThat(severity.level(0)).isNull();
        assertThat(severity.level(4)).isNull();
        assertThat(severity.level(null)).isNull();
        assertThat(new DtddTopicSeverityDto(null, List.of(), List.of(), 0, null).verifiedLevel()).isNull();
    }

    @Test
    void cachedTopics_withoutTheirSeverities_haveNone() {
        DtddTopicsCacheEntryDto entry = new DtddTopicsCacheEntryDto(7, "Halo", List.of("gore"), List.of(), List.of(),
                Instant.EPOCH, false);

        assertThat(entry.severities()).isNull();
    }

    @Test
    void dtddTopics_withoutThresholds_haveNone() {
        assertThat(new DtddTopicsBody(Set.of("gore")).minSeverities()).isNull();
        assertThat(json.readValue("{\"topics\":[\"gore\"]}", DtddTopicsBody.class).minSeverities()).isNull();
    }

    @Test
    void aGameMapping_withoutItsIgnore_doesNotIgnoreTheGame() {
        GameMappingRequest request = new GameMappingRequest("STEAM", "730", "1", "CS", null, null, false);
        assertThat(request.ignoredByDefault()).isFalse();
        assertThat(new GameMappingRequest("STEAM", "730", null, null, null, null, false, null).ignoredByDefault())
                .isFalse();
        assertThat(json.readValue("{\"sourceType\":\"STEAM\",\"sourceId\":\"730\",\"applyLive\":true}",
                GameMappingRequest.class).ignoredByDefault()).isFalse();

        GameMappingDto mapping = new GameMappingDto(null, "STEAM", "730", "CS2", "1", "CS", null, null, 3,
                Instant.EPOCH, "admin");
        assertThat(mapping.ignoredByDefault()).isFalse();
        assertThat(json.readValue("{\"sourceType\":\"STEAM\",\"sourceId\":\"730\",\"bindingCount\":0}",
                GameMappingDto.class).ignoredByDefault()).isFalse();
    }

    @Test
    void aRecalculation_countsWhatItWentThrough() {
        TwRebuildStatusDto status = new TwRebuildStatusDto("RUNNING", "NON_OVERRIDDEN", 40, 10, 3, 1, Instant.EPOCH,
                null, null);

        assertThat(status.done()).isEqualTo(14L);
        assertThat(TwRebuildStatusDto.IDLE.done()).isZero();
        assertThat(TwRebuildStatusDto.IDLE.state()).isEqualTo("IDLE");
    }

    @Test
    void aKeysUsage_travelsFromItsStatusToThePage() {
        var usage = new fr.enimaloc.catapult.common.dto.admin.KeyUsageDto(2, 3, 5, null, "DAY", 40, 100_000L, null);
        var status = new fr.enimaloc.catapult.common.dto.admin.KeyStatus("id", "ab…cd", null, false, 0, usage);
        var page = fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto.of(
                new fr.enimaloc.catapult.common.dto.admin.SteamKeysPageData(java.util.List.of(status), true));

        org.assertj.core.api.Assertions.assertThat(page.keys().getFirst().usage()).isEqualTo(usage);
        org.assertj.core.api.Assertions.assertThat(new fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto.Key(
                "id", "ab…cd", null, false, 0).usage()).isNull();
        org.assertj.core.api.Assertions.assertThat(new fr.enimaloc.catapult.common.dto.admin.KeyStatus(
                "id", "ab…cd", null, false, 0).usage()).isNull();
    }
}
