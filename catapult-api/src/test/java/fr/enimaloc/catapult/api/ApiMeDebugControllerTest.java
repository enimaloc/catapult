package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.MyDebugDto;
import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiMeDebugControllerTest {

    private final MemberDebugService service = mock(MemberDebugService.class);
    private final ApiMeDebugController controller = new ApiMeDebugController(service);
    private final UUID me = UUID.randomUUID();

    private Jwt jwt() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(me.toString());
        return jwt;
    }

    private static MemberDebugSession session(boolean active) {
        MemberDebugSession s = new MemberDebugSession();
        s.setActive(active);
        return s;
    }

    @Test
    void meDebug_reflectsTheCallersOwnState() {
        when(service.state(me)).thenReturn(Optional.of(session(true)));

        assertThat(controller.debug(jwt())).isEqualTo(new MyDebugDto(true));
    }

    @Test
    void meDebug_isOffWhenSwitchedOffNeverEnabledOrAnonymous() {
        when(service.state(me)).thenReturn(Optional.of(session(false)));
        assertThat(controller.debug(jwt())).isEqualTo(new MyDebugDto(false));

        when(service.state(me)).thenReturn(Optional.empty());
        assertThat(controller.debug(jwt())).isEqualTo(new MyDebugDto(false));

        assertThat(controller.debug(null)).isEqualTo(new MyDebugDto(false));
    }
}
