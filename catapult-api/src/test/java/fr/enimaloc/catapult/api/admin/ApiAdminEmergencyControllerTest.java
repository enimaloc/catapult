package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyReportDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import fr.enimaloc.catapult.service.emergency.EmergencyStopService;
import fr.enimaloc.catapult.service.emergency.EmergencyViews;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAdminEmergencyControllerTest {

    private final EmergencyViews views = mock(EmergencyViews.class);
    private final EmergencyStopService stops = mock(EmergencyStopService.class);
    private final ApiUserResolver users = mock(ApiUserResolver.class);
    private final ApiAdminEmergencyController controller = new ApiAdminEmergencyController(views, stops, users);
    private final Jwt jwt = mock(Jwt.class);

    private UserAccount admin() {
        UserAccount admin = new UserAccount();
        admin.setTwitchUsername("theowner");
        when(users.viewer(jwt)).thenReturn(admin);
        return admin;
    }

    @Test
    void stops_areListedActiveByDefaultAndAsHistoryOnRequest() {
        EmergencyStopDto dto = new EmergencyStopDto(UUID.randomUUID(), "ALL", "GLOBAL", "OWNER", null,
                Instant.now(), null, null, null);
        when(views.stops(true)).thenReturn(List.of(dto));
        when(views.stops(false)).thenReturn(List.of());

        assertThat(controller.stops(true)).containsExactly(dto);
        assertThat(controller.stops(false)).isEmpty();
    }

    @Test
    void reports_arePagedThroughTheViews() {
        EmergencyReportDto dto = new EmergencyReportDto(UUID.randomUUID(), "streamer", "STEAM", "viewer",
                Instant.now(), false, null, "{}");
        when(views.reports(2)).thenReturn(List.of(dto));

        assertThat(controller.reports(2)).containsExactly(dto);
    }

    @Test
    void stop_isAnOwnerStopByTheCaller() {
        admin();
        ProviderEmergencyStop stop = new ProviderEmergencyStop();
        stop.setProvider("STEAM");
        stop.setScope(ProviderEmergencyStop.Scope.GLOBAL);
        stop.setOrigin(ProviderEmergencyStop.Origin.OWNER);
        when(stops.ownerStop("steam", "theowner")).thenReturn(stop);

        controller.stop(jwt, Map.of("provider", "steam"));

        verify(stops).ownerStop("steam", "theowner");
    }

    @Test
    void stop_anUnknownProviderIsABadRequest() {
        admin();
        when(stops.ownerStop("nope", "theowner")).thenThrow(new IllegalArgumentException("nope"));

        assertThatThrownBy(() -> controller.stop(jwt, Map.of("provider", "nope")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void stop_withoutAProviderIsABadRequest() {
        assertThatThrownBy(() -> controller.stop(jwt, Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void resume_liftsTheProviderStopsAndSaysHowMany() {
        admin();
        when(stops.ownerResume("steam", "theowner")).thenReturn(2);

        assertThat(controller.resume(jwt, "steam")).containsEntry("lifted", 2);
    }
}
