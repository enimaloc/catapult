package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.debug.DebugMembers;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in user's OBS connection for obs-session.js, which connects the page to OBS with
 * it. 204 when logged out, OBS isn't enabled, or an admin impersonates a member (their browser
 * isn't the member's OBS): the script then stays idle. Its debugCapture is the page's data-debug-capture: whether the
 * member is in debug mode, the page then recording its OBS and Twitchat exchanges.
 */
@RestController
@RequiredArgsConstructor
public class ObsSessionController {
    private final ApiService apiService;
    private final ObjectProvider<DebugMembers> debugMembers;

    @GetMapping("/me/obs")
    public ResponseEntity<TwitchatWidgetConfig> obs(HttpServletRequest request) {
        TwitchatWidgetConfig connection = ImpersonationController.impersonating(request) ? null : apiService.obsConnection();
        if (connection == null) {
            return ResponseEntity.noContent().build();
        }
        DebugMembers debug = debugMembers.getIfAvailable();
        // Carries the OBS password: never kept by the browser cache.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(connection.withDebugCapture(debug != null && debug.isCurrentActive()));
    }
}
