package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.Notification;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.NotificationRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.NotificationDto;
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

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ApiAdminNotificationsController {

    private final NotificationService service;
    private final NotificationRepository repo;
    private final UserAccountRepository userRepo;

    @PostMapping
    public ResponseEntity<NotificationDto> create(@RequestBody CreateBody body, @AuthenticationPrincipal Jwt jwt) {
        UserAccount admin = currentUser(jwt);
        NotificationService.CreateRequest req = new NotificationService.CreateRequest(
                body.title(), body.body(), body.severity(),
                body.ctaUrl(), body.ctaLabel(), body.expiresAt(), body.targetUserId());
        NotificationDto dto = service.create(req, admin);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping
    public Page<Notification> list(Pageable pageable) {
        return repo.findAllByOrderByCreatedAtDesc(pageable);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        Notification n = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(n);
    }

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userRepo.findByTwitchId(twitchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public record CreateBody(
            String title,
            String body,
            Notification.Severity severity,
            String ctaUrl,
            String ctaLabel,
            Instant expiresAt,
            UUID targetUserId
    ) {}
}
