package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.common.dto.channel.ConfigResponse;
import fr.enimaloc.catapult.common.dto.notification.NotificationFlushResultDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/twitchat")
@RequiredArgsConstructor
public class ApiTwitchatWidgetController {

    private final TwitchatWidgetAccess widgetAccess;
    private final TwitchatActionExecutor actionExecutor;

    @GetMapping("/widget/{uuid}/access")
    public ResponseEntity<TwitchatWidgetAccessResponse> access(@PathVariable UUID uuid) {
        return widgetAccess.access(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/widget/{uuid}")
    public ResponseEntity<ConfigResponse> config(@PathVariable UUID uuid) {
        return widgetAccess.config(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The widget's owner's pending Twitchat notifications, sent once catapult-web has the OBS widget connected. */
    @PostMapping("/widget/{uuid}/notifications/flush")
    public ResponseEntity<NotificationFlushResultDto> flushNotifications(@PathVariable UUID uuid) {
        return widgetAccess.flush(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/actions/{token}")
    public ResponseEntity<Map<String, String>> executeAction(@PathVariable UUID token) {
        TwitchatActionExecutor.Result result = actionExecutor.execute(token);
        return ResponseEntity.ok(Map.of("result", result.name()));
    }
}
