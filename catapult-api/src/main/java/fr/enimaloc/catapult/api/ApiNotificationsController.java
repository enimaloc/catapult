package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.NotificationDto;
import fr.enimaloc.catapult.service.notification.NotificationService;
import fr.enimaloc.catapult.service.notification.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class ApiNotificationsController {

    private final NotificationService service;
    private final SseEmitterRegistry registry;
    private final UserAccountRepository userRepo;

    @GetMapping
    public Page<NotificationDto> list(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return service.listForUser(currentUser(jwt).getId(), pageable);
    }

    @GetMapping("/unread-count")
    public long unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return service.unreadCount(currentUser(jwt).getId());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.markRead(currentUser(jwt).getId(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(currentUser(jwt).getId());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return registry.register(currentUser(jwt).getId());
    }

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userRepo.findByTwitchId(twitchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
