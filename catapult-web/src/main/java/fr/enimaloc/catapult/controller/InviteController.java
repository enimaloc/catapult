package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The member's invitations live in their channel page's Invitations tab; {@code /invite}, where the
 * old site's links point, only leads there.
 */
@Controller
@RequiredArgsConstructor
public class InviteController {
    private final ApiService apiService;

    /** 401 (error page) for a visitor; else a redirect to the member's own channel, on its tab. */
    @GetMapping("/invite")
    public String invite() {
        String login = ApiClient.currentLogin();
        if (login == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return "redirect:/channel/" + login + "#invitations";
    }

    /** Answered with catapult-api's status (204; 403 without the right to invite), 502 when unreachable. */
    @PostMapping("/invite/regenerate")
    public ResponseEntity<Void> regenerate() {
        if (!ApiClient.hasSession()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.status(apiService.regenerateInvite()).build();
    }
}
