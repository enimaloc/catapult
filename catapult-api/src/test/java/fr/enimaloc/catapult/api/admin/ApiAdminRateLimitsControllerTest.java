package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitCorrectionDto;
import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;
import fr.enimaloc.catapult.ratelimit.LearnedRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiAdminRateLimitsControllerTest {

    private static final String HOST = "HOST store.steampowered.com";
    private static final LearnedRateLimitDto ROW = new LearnedRateLimitDto(HOST, "store.steampowered.com", null, null,
            null, false, 20, 60, true, 30_000, false, 1, null, 0, null);

    private final LearnedRateLimiter limiter = mock(LearnedRateLimiter.class);
    private final ApiAdminRateLimitsController controller = new ApiAdminRateLimitsController(limiter);

    @Test
    void theLearnedLimits_areListedCorrectedAndReset() {
        when(limiter.list()).thenReturn(List.of(ROW));
        when(limiter.correct(HOST, 20, 60, null, "ROUTE")).thenReturn(ROW);
        when(limiter.reset(HOST)).thenReturn(ROW);

        assertThat(controller.list()).containsExactly(ROW);
        assertThat(controller.correct(HOST, new LearnedRateLimitCorrectionDto(20, 60, null, "ROUTE"))).isEqualTo(ROW);
        assertThat(controller.reset(HOST)).isEqualTo(ROW);
        assertThat(ROW.hostScope()).isTrue();
    }
}
