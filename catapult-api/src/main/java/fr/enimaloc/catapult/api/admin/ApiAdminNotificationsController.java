package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.repository.notification.NotificationRepository;
import fr.enimaloc.catapult.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/notifications")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminNotificationsController {

    private final NotificationService service;
    private final NotificationRepository repo;
    private final ApiUserResolver userResolver;
    private final fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository commandRepository;

    /**
     * 201 with the notification; for {@link AdminNotificationCreateBody#CHAT_COMMANDS}, 201 with how
     * many streamers were told (one notification each).
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody AdminNotificationCreateBody body, @AuthenticationPrincipal Jwt jwt) {
        UserAccount admin = userResolver.viewerByTwitchId(jwt);
        NotificationService.CreateRequest req = new NotificationService.CreateRequest(
                body.title(), body.body(), body.severity(),
                body.ctaUrl(), body.ctaLabel(), body.expiresAt(), body.targetUserId());
        if (AdminNotificationCreateBody.CHAT_COMMANDS.equals(body.audience())) {
            int sent = service.createForEach(req, admin, commandRepository.findActiveUsersWithEnabledCommands());
            return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of("sent", sent));
        }
        NotificationDto dto = service.create(req, admin);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
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
        Notification n = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(n);
    }

}
