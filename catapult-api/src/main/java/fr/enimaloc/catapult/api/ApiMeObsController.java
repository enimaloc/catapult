package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The logged-in user's OBS-websocket connection, password included, for their own browser to
 * connect to OBS with (catapult-web's obs-session.js). 204 rather than 404 when OBS isn't
 * enabled: that's the common case, and catapult-web logs every 4xx it gets back.
 */
@RestController
@RequiredArgsConstructor
public class ApiMeObsController {

    private final TwitchatWidgetSettingsService twitchatWidgetSettingsService;

    @GetMapping("/api/me/obs")
    public ResponseEntity<TwitchatWidgetConfig> obs(@AuthenticationPrincipal Jwt jwt) {
        // No JWT on the unauthenticated localhost calls the security config lets through.
        if (jwt == null) {
            return ResponseEntity.noContent().build();
        }
        return twitchatWidgetSettingsService.enabledConfig(UUID.fromString(jwt.getSubject()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
