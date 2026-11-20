package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.DryRunStateDto;
import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.service.dryrun.DryRunService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMeDryRunControllerTest {

    private final DryRunService service = mock(DryRunService.class);
    private final ApiMeDryRunController controller = new ApiMeDryRunController(service);
    private final UUID me = UUID.randomUUID();

    @Test
    void state_isTheMembersOwn() {
        MemberDryRun row = new MemberDryRun();
        row.setUserId(me);
        row.setEnabledAt(Instant.parse("2026-11-20T10:00:00Z"));
        when(service.state(me)).thenReturn(Optional.of(row));

        assertThat(controller.state(me)).isEqualTo(new DryRunStateDto(true, Instant.parse("2026-11-20T10:00:00Z")));
    }

    @Test
    void state_isOffWithNoDateWhenNotInDryRun() {
        when(service.state(me)).thenReturn(Optional.empty());

        assertThat(controller.state(me)).isEqualTo(new DryRunStateDto(false, null));
    }

    @Test
    void disable_takesTheCallerOutOfTheirOwnDryRun_behindIsSelf() throws NoSuchMethodException {
        controller.disable(me);

        verify(service).disable(me, me);
        for (String name : new String[] {"state", "disable"}) {
            Method method = ApiMeDryRunController.class.getMethod(name, UUID.class);
            assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("@authz.isSelf(#userId)");
        }
        assertThat(ApiMeDryRunController.class.getMethod("state", UUID.class).getAnnotation(GetMapping.class).value())
                .containsExactly(ApiPaths.USER + "/dry-run");
        assertThat(ApiMeDryRunController.class.getMethod("disable", UUID.class).getAnnotation(PostMapping.class)
                .value()).containsExactly(ApiPaths.USER + "/dry-run/disable");
    }

    @Test
    void aMemberCannotPutThemselvesInDryRun() {
        assertThat(Arrays.stream(ApiMeDryRunController.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("enable");
    }
}
