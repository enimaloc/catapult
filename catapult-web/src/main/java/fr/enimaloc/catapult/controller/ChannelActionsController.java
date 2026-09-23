package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing mutation endpoints for the channel dashboard page. These exist because
 * the session JWT ApiClient authenticates with never reaches the browser (see ApiClient's
 * HttpSession lookup) — the browser can only call back into catapult-web itself, which
 * then forwards to catapult-api with the server-held JWT via ApiService.
 */
@RestController
@RequiredArgsConstructor
public class ChannelActionsController {
    private final ApiService apiService;

    @PostMapping("/channel/{username}/settings/bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(@PathVariable String username) {
        apiService.toggleBot(username);
    }

    @PostMapping("/channel/{username}/game/recheck")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recheckGame(@PathVariable String username) {
        apiService.recheckGame(username);
    }
}
