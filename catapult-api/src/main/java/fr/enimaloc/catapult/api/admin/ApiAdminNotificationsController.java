package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountDto;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountRequest;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.notification.AdminNotificationService;
import fr.enimaloc.catapult.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import fr.enimaloc.catapult.api.ApiPaths;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/notifications")
@RequiredArgsConstructor
public class ApiAdminNotificationsController {

    private final NotificationService service;
    private final AdminNotificationService admin;
    private final ApiUserResolver userResolver;

    /**
     * 201 with the notification; for {@link AdminNotificationCreateBody#CHAT_COMMANDS}, 201 with how
     * many streamers were told (one notification each); for {@link AdminNotificationCreateBody#EXPERIMENT},
     * 201 with how many participants share one notification.
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody AdminNotificationCreateBody body, @AuthenticationPrincipal Jwt jwt) {
        UserAccount caller = userResolver.viewerByTwitchId(jwt);
        return switch (admin.create(body, caller)) {
            case AdminNotificationService.Sent sent ->
                    ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sent", sent.sent()));
            case AdminNotificationService.Single single -> ResponseEntity.status(HttpStatus.CREATED).body(single.dto());
        };
    }

    /** How many members an audience would reach, so the admin sees it before sending; experiment audiences only. */
    @PostMapping("/audience-count")
    public AudienceCountDto audienceCount(@RequestBody AudienceCountRequest body) {
        return admin.audienceCount(body);
    }

    @GetMapping
    public Page<AdminNotificationDto> list(Pageable pageable) {
        return service.adminPage(pageable);
    }

    @PostMapping("/preview")
    public NotificationPreviewDto preview(@RequestBody NotificationPreviewRequest body) {
        return service.preview(body.body());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        admin.delete(id);
    }

}
