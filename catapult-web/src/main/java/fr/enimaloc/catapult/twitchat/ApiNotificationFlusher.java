package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.common.dto.notification.NotificationFlushResultDto;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** {@link NotificationFlusher} over catapult-api. Not in mock mode, which has no pending deliveries. */
@Component
@Profile("!mock")
@RequiredArgsConstructor
public class ApiNotificationFlusher implements NotificationFlusher {

    private final ApiClient client;

    @Override
    public int flushPage(String jwt) {
        UUID owner = ApiClient.subject(jwt);
        if (owner == null) return HttpStatus.UNAUTHORIZED.value();
        return client.callAs(HttpMethod.POST, "/api/internal/users/{userId}/twitchat-notifications/flush", jwt,
                NotificationFlushResultDto.class, owner).status();
    }

    @Override
    public int flushWidget(UUID token) {
        return client.callAs(HttpMethod.POST, "/api/twitchat/widget/{token}/notifications/flush", null,
                NotificationFlushResultDto.class, token).status();
    }
}
