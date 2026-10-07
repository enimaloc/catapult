package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.NotificationDto;
import fr.enimaloc.catapult.common.dto.NotificationSnapshotDto;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class ApiNotificationsController {

    private final NotificationService service;
    private final ApiUserResolver userResolver;

    @GetMapping
    public Page<NotificationDto> list(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return service.listForUser(userResolver.viewerByTwitchId(jwt).getId(), pageable);
    }

    @GetMapping("/unread-count")
    public long unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return service.unreadCount(userResolver.viewerByTwitchId(jwt).getId());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.markRead(userResolver.viewerByTwitchId(jwt).getId(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(userResolver.viewerByTwitchId(jwt).getId());
    }

    @GetMapping("/snapshot")
    public NotificationSnapshotDto snapshot(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userResolver.viewerByTwitchId(jwt).getId();
        List<NotificationDto> items = service.findRecent(userId, 10);
        long unread = service.countUnread(userId);
        return new NotificationSnapshotDto(items, unread);
    }

}
