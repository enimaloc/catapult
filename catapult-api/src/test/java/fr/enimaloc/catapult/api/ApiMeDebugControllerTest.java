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
        s.setReplayRate(0.25);
        return s;
    }

    @Test
    void meDebug_reflectsTheCallersOwnState_andReplayRate() {
        when(service.state(me)).thenReturn(Optional.of(session(true)));

        assertThat(controller.debug(UUID.randomUUID(), jwt())).isEqualTo(new MyDebugDto(true, 0.25));
    }

    @Test
    void meDebug_isOffWithNoReplayWhenSwitchedOffOrNeverEnabled() {
        when(service.state(me)).thenReturn(Optional.of(session(false)));
        assertThat(controller.debug(UUID.randomUUID(), jwt())).isEqualTo(new MyDebugDto(false, 0));

        when(service.state(me)).thenReturn(Optional.empty());
        assertThat(controller.debug(UUID.randomUUID(), jwt())).isEqualTo(new MyDebugDto(false, 0));
    }
}
