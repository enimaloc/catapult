package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyReportDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.service.emergency.EmergencyStopService;
import fr.enimaloc.catapult.service.emergency.EmergencyViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** The provider emergency stops and the reports behind them: read, and the owner's stop and resume. */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/emergency")
@RequiredArgsConstructor
public class ApiAdminEmergencyController {

    private final EmergencyViews views;
    private final EmergencyStopService stopService;
    private final ApiUserResolver userResolver;

    /** {@code active=false}: the history of lifted and expired stops. */
    @GetMapping("/stops")
    public List<EmergencyStopDto> stops(@RequestParam(defaultValue = "true") boolean active) {
        return views.stops(active);
    }

    @GetMapping("/reports")
    public List<EmergencyReportDto> reports(@RequestParam(defaultValue = "0") int page) {
        return views.reports(page);
    }

    /** Body {@code {"provider": "<provider>|ALL"}}: stops it instance-wide until resumed. */
    @PostMapping("/stops")
    public EmergencyStopDto stop(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, String> body) {
        String provider = body.get("provider");
        if (provider == null || provider.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A provider is required");
        }
        String login = userResolver.viewer(jwt).getTwitchUsername();
        try {
            var stop = stopService.ownerStop(provider, login);
            return new EmergencyStopDto(stop.getId(), stop.getProvider(), stop.getScope().name(),
                    stop.getOrigin().name(), null, stop.getCreatedAt(), stop.getExpiresAt(), stop.getLiftedAt(),
                    stop.getLiftedBy());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider", e);
        }
    }

    /** Lifts the stops of a provider (or of {@code ALL}); answers how many were lifted. */
    @DeleteMapping("/stops")
    public Map<String, Integer> resume(@AuthenticationPrincipal Jwt jwt, @RequestParam String provider) {
        String login = userResolver.viewer(jwt).getTwitchUsername();
        try {
            return Map.of("lifted", stopService.ownerResume(provider, login));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider", e);
        }
    }
}
