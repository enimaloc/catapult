package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The channel page's "resume" button, forwarded to catapult-api with the session's JWT (the channel's owner alone,
 * which catapult-api checks). Answers its status like the other channel actions; 502 when it is unreachable.
 */
@RestController
@RequiredArgsConstructor
public class ChannelEmergencyController {
    private final ApiService apiService;

    @PostMapping("/channel/{username}/emergency/resume")
    public ResponseEntity<Void> resume(@PathVariable String username) {
        return ApiStatus.answer(apiService.resumeChannelEmergency(username));
    }
}
