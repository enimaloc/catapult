package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.twitchat.TwitchatDispatchHub;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * The logged-in owner's Twitchat dispatches, for twitchat-stream.js; 204 when logged out, 404 while
 * an admin impersonates a member (the dispatches carry the member's signed action tokens).
 */
@RestController
@RequiredArgsConstructor
public class TwitchatEventStreamController {

    private final ApiService apiService;
    private final TwitchatDispatchHub hub;

    @GetMapping(value = "/me/twitchat/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(HttpServletRequest request) {
        if (ImpersonationController.impersonating(request)) {
            return ResponseEntity.notFound().build();
        }
        UUID userId = apiService.currentUserId();
        return userId == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(hub.subscribe(userId));
    }
}
