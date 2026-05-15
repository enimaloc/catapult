package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ObsProcessCache;
import fr.enimaloc.catapult.service.ObsSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ObsApiControllerTest {

    @Mock private ObsProcessCache obsProcessCache;

    @InjectMocks private ObsApiController controller;

    @Test
    void receiveProcesses_updatesCache() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        var payload = new ObsApiController.ProcessesPayload(
                "WINDOWS",
                Map.of("APPDATA", "C:\\Users\\Alice\\AppData\\Roaming"),
                List.of(
                        new ObsApiController.ProcessesPayload.ProcessEntry("hl2.exe", "C:\\Games\\HL2", "hl2.exe -game hl2"),
                        new ObsApiController.ProcessesPayload.ProcessEntry("steam.exe", null, null)
                )
        );

        controller.receiveProcesses(auth, payload);

        ArgumentCaptor<ObsSession> captor = ArgumentCaptor.forClass(ObsSession.class);
        verify(obsProcessCache).update(org.mockito.ArgumentMatchers.eq(user), captor.capture());

        ObsSession captured = captor.getValue();
        assertThat(captured.os()).isEqualTo("WINDOWS");
        assertThat(captured.environment()).containsEntry("APPDATA", "C:\\Users\\Alice\\AppData\\Roaming");
        assertThat(captured.processes()).extracting(ObsSession.ProcessSnapshot::name)
                .containsExactlyInAnyOrder("hl2.exe", "steam.exe");
    }

    @Test
    void receiveProcesses_emptyList_clearsCache() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        controller.receiveProcesses(auth, new ObsApiController.ProcessesPayload(null, null, List.of()));

        verify(obsProcessCache).clear(user);
    }
}
