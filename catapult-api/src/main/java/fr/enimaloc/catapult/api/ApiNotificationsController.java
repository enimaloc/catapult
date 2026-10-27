package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationSnapshotDto;
import fr.enimaloc.catapult.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping(ApiPaths.USER + "/notifications")
@RequiredArgsConstructor
public class ApiNotificationsController {

    private final NotificationService service;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public Page<NotificationDto> list(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return service.listForUser(userResolver.viewerByTwitchId(jwt).getId(), pageable);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping("/unread-count")
    public long unreadCount(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return service.unreadCount(userResolver.viewerByTwitchId(jwt).getId());
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.markRead(userResolver.viewerByTwitchId(jwt).getId(), id);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(userResolver.viewerByTwitchId(jwt).getId());
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping("/snapshot")
    public NotificationSnapshotDto snapshot(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UUID viewerId = userResolver.viewerByTwitchId(jwt).getId();
        List<NotificationDto> items = service.findRecent(viewerId, 10);
        long unread = service.countUnread(viewerId);
        return new NotificationSnapshotDto(items, unread);
    }

}
