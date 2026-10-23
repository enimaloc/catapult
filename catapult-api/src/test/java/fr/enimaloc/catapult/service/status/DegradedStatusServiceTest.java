package fr.enimaloc.catapult.service.status;

import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.config.DegradedMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DegradedStatusServiceTest {

    private DegradedStatusService service(boolean degraded, int pollingSeconds, boolean minecraftEnabled, long minecraftSyncMs) {
        MockEnvironment env = new MockEnvironment();
        if (degraded) {
            env.setActiveProfiles(DegradedMode.PROFILE);
        }
        return new DegradedStatusService(env, pollingSeconds, minecraftEnabled, minecraftSyncMs);
    }

    @Test
    void notDegraded_tellsNothingElse() {
        assertThat(service(false, 30, true, 300_000).status())
                .isEqualTo(new DegradedStatusDto(false, null, null));
    }

    @Test
    void degraded_tellsTheAppliedIntervals() {
        assertThat(service(true, 60, true, 900_000).status())
                .isEqualTo(new DegradedStatusDto(true, 60, 15));
    }

    @Test
    void degraded_withoutMinecraft_leavesItsLineOut() {
        assertThat(service(true, 60, false, 900_000).status())
                .isEqualTo(new DegradedStatusDto(true, 60, null));
    }

    @Test
    void minutes_roundUp() {
        assertThat(service(true, 60, true, 90_000).status().minecraftFriendsSyncMinutes()).isEqualTo(2);
    }
}
