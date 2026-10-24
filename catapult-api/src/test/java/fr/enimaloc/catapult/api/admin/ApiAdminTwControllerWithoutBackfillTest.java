package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import fr.enimaloc.catapult.service.tw.AdminTwService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** With tw.enabled=false there is no backfill bean: the rebuild endpoints refuse with a 503 and do nothing else. */
class ApiAdminTwControllerWithoutBackfillTest {

    private final ApiAdminTwController controller = new ApiAdminTwController(
            mock(AdminTwService.class), Optional.empty(), mock(AdminEventPublisher.class));

    @Test
    void rebuildNonOverridden_withoutBackfill_isServiceUnavailable() {
        assertUnavailable(controller::rebuildNonOverriddenMappings);
    }

    @Test
    void rebuildAll_withoutBackfill_isServiceUnavailable() {
        assertUnavailable(controller::rebuildMappings);
    }

    @Test
    void rebuildIncludingPinned_withoutBackfill_isServiceUnavailable() {
        assertUnavailable(controller::rebuildMappingsIncludingPinned);
    }

    private static void assertUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getReason()).isEqualTo("The TW backfill is not available");
        });
    }
}
