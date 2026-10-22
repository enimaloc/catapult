package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.notification.NotificationChannelsDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationFlushResultDto;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryDispatcher;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The channels the logged-in member also receives notifications through, besides the site, and
 * the flush of their pending Twitchat ones, asked by catapult-web once their Twitchat page is
 * connected (the OBS widget's flavour is ApiTwitchatWidgetController's).
 */
@RestController
@RequiredArgsConstructor
public class ApiMeNotificationChannelsController {

    private final NotificationDeliveryService deliveryService;
    private final NotificationDeliveryDispatcher deliveryDispatcher;
    private final ApiUserResolver userResolver;

    @GetMapping("/api/me/notification-channels")
    public NotificationChannelsDto channels(@AuthenticationPrincipal Jwt jwt) {
        return new NotificationChannelsDto(deliveryService.channels(userResolver.viewer(jwt).getId()));
    }

    @PutMapping("/api/me/notification-channels")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replace(@AuthenticationPrincipal Jwt jwt, @RequestBody NotificationChannelsDto body) {
        deliveryService.replaceChannels(userResolver.viewer(jwt), body.channels());
    }

    @PostMapping("/api/me/twitchat/notifications/flush")
    public NotificationFlushResultDto flush(@AuthenticationPrincipal Jwt jwt) {
        return new NotificationFlushResultDto(deliveryDispatcher.flushTwitchat(userResolver.viewer(jwt)));
    }
}
