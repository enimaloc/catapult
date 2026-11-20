package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.DryRunStateDto;
import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.service.dryrun.DryRunService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAdminMemberDryRunControllerTest {

    private final DryRunService service = mock(DryRunService.class);
    private final ApiAdminMemberDryRunController controller = new ApiAdminMemberDryRunController(service);
    private final UUID member = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    private Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(admin.toString()).build();
    }

    @Test
    void enable_needsAJwt_andSitsUnderTheAdminPrefix() {
        // the admin prefix is what ApiSecurityConfig keeps for admins
        assertThat(ApiAdminMemberDryRunController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly(ApiPaths.ADMIN + "/members/{id}/dry-run");
        assertThatThrownBy(() -> controller.enable(member, null)).isInstanceOfSatisfying(
                ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(service, never()).enable(any(), any());
    }

    @Test
    void enable_namesTheAdmin_andAnswersTheState() {
        MemberDryRun row = new MemberDryRun();
        row.setUserId(member);
        row.setEnabledBy(admin);
        row.setEnabledAt(Instant.parse("2026-11-20T10:00:00Z"));
        when(service.enable(admin, member)).thenReturn(row);

        assertThat(controller.enable(member, jwt()))
                .isEqualTo(new DryRunStateDto(true, Instant.parse("2026-11-20T10:00:00Z")));
    }

    @Test
    void disable_namesTheAdminWhenKnown() {
        controller.disable(member, jwt());
        verify(service).disable(admin, member);

        controller.disable(member, null);
        verify(service).disable(null, member);
    }
}
