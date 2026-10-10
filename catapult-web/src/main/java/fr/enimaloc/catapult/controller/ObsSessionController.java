package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in user's OBS connection for obs-session.js, which connects the page to OBS with
 * it. 204 when logged out or OBS isn't enabled: the script then stays idle.
 */
@RestController
@RequiredArgsConstructor
public class ObsSessionController {
    private final ApiService apiService;

    @GetMapping("/me/obs")
    public ResponseEntity<TwitchatWidgetConfig> obs() {
        TwitchatWidgetConfig connection = apiService.obsConnection();
        if (connection == null) {
            return ResponseEntity.noContent().build();
        }
        // Carries the OBS password: never kept by the browser cache.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(connection);
    }
}
