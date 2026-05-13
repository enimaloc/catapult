package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ObsProcessCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Set;
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

        controller.receiveProcesses(auth, new ObsApiController.ProcessesPayload(List.of("hl2.exe", "steam.exe")));

        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(obsProcessCache).update(org.mockito.ArgumentMatchers.eq(user), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("hl2.exe", "steam.exe");
    }

    @Test
    void receiveProcesses_emptyList_clearsCache() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        controller.receiveProcesses(auth, new ObsApiController.ProcessesPayload(List.of()));

        verify(obsProcessCache).clear(user);
    }
}
