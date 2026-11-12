package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.service.dtdd.DtddCacheAdminService;
import fr.enimaloc.catapult.service.dtdd.DtddCacheKind;
import fr.enimaloc.catapult.service.dtdd.DtddCacheRefetchJob;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ApiAdminDtddCachesControllerTest {

    private final DtddCacheAdminService caches = mock(DtddCacheAdminService.class);
    private final DtddCacheRefetchJob job = mock(DtddCacheRefetchJob.class);
    private final ApiAdminDtddCachesController controller = new ApiAdminDtddCachesController(caches, job);

    @Test
    void aCache_isNamedByItsKind() {
        doReturn(Page.empty()).when(caches).list(DtddCacheKind.SEARCHES, "halo", 2, 50);

        assertThat(controller.list("searches", "halo", 2, 50)).isEmpty();
        controller.refetch("games", "7");
        verify(caches).refetch(DtddCacheKind.GAMES, "7");
        controller.entry("topics", "7");
        verify(caches).detail(DtddCacheKind.TOPICS, "7");
    }

    @Test
    void anotherKind_isNotFound() {
        assertThatThrownBy(() -> controller.list("keys", null, 0, 20))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(caches);
    }

    @Test
    void theBulkRefetch_isStartedFollowedAndCancelled() {
        DtddRefetchJobDto running = new DtddRefetchJobDto("RUNNING", 3, 0, 0, null, null, null, null);
        when(job.start()).thenReturn(running);
        when(job.status()).thenReturn(running);
        when(job.cancel()).thenReturn(running);

        assertThat(controller.startJob()).isEqualTo(running);
        assertThat(controller.job()).isEqualTo(running);
        assertThat(controller.cancelJob()).isEqualTo(running);
    }
}
