package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.twitchat.TwitchatDispatchHub;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.UUID;

/**
 * The page an OBS browser source opens, /widget/obs/{token}: anonymous (OBS has no session), it
 * relays the owner's Twitchat dispatches to OBS like a dashboard would, through the same
 * scripts reading this widget's config and stream instead of /me/obs and /me/twitchat/events.
 * A token that isn't a UUID, is unknown or whose widget is off gets the same bare page with no
 * OBS connection, its config 204 and its stream 404: never an error page. The stream closes
 * every {@link #STREAM_TIMEOUT}, so revoking the link takes effect on the reconnection.
 */
@Controller
@RequiredArgsConstructor
public class WidgetObsController {

    /**
     * How long a widget stream stays open: its EventSource then reconnects and the token is checked
     * again, so a regenerated or disabled link stops receiving dispatches within that time.
     */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

    private final ApiService apiService;
    private final TwitchatDispatchHub hub;

    @GetMapping("/widget/obs/{token}")
    public String page(@PathVariable String token, Model model) {
        UUID uuid = uuid(token);
        model.addAttribute("token", uuid != null && owner(uuid) != null ? uuid.toString() : null);
        return "widget/obs";
    }

    @GetMapping("/widget/obs/{token}/config")
    @ResponseBody
    public ResponseEntity<TwitchatWidgetConfig> config(@PathVariable String token) {
        UUID uuid = uuid(token);
        TwitchatWidgetConfig config = uuid == null ? null : apiService.widgetObsConfig(uuid);
        if (config == null) {
            return ResponseEntity.noContent().build();
        }
        // Carries the OBS password: never kept by a cache.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(config);
    }

    @GetMapping(value = "/widget/obs/{token}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public ResponseEntity<SseEmitter> events(@PathVariable String token) {
        UUID uuid = uuid(token);
        UUID owner = uuid == null ? null : owner(uuid);
        return owner == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(hub.subscribe(owner, STREAM_TIMEOUT));
    }

    /** The link OBS sources were set up with before /widget/obs. */
    @GetMapping("/widget/twitchat/{token}")
    public ResponseEntity<Void> oldLink(@PathVariable String token) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(UriComponentsBuilder.fromPath("/widget/obs/{token}").buildAndExpand(token).encode().toUri())
                .build();
    }

    /** The widget's owner when it is on; null when unknown, off or catapult-api unreachable. */
    private UUID owner(UUID token) {
        TwitchatWidgetAccessResponse access = apiService.widgetAccess(token);
        return access == null || !access.enabled() ? null : uuid(access.ownerId());
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
