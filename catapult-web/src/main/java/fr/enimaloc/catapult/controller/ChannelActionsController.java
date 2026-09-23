package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.UpdateBindingRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/channel/{username}/bindings/{bindingId}/ccl-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cclToggle(@PathVariable String username, @PathVariable String bindingId,
                           @RequestBody CclToggleRequest body) {
        apiService.cclToggle(username, bindingId, body.enabled());
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/ignored-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ignoredToggle(@PathVariable String username, @PathVariable String bindingId,
                               @RequestBody IgnoredToggleRequest body) {
        apiService.ignoredToggle(username, bindingId, body.ignored());
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(@PathVariable String username, @PathVariable String bindingId) {
        apiService.deleteBinding(username, bindingId);
    }

    @GetMapping("/channel/{username}/games/search")
    public Object searchGames(@PathVariable String username, @RequestParam(defaultValue = "") String q) {
        return apiService.searchGames(username, q);
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBinding(@PathVariable String username, @PathVariable String bindingId,
                               @RequestBody UpdateBindingRequest body) {
        apiService.updateBinding(username, bindingId, body.twitchGameId(), body.twitchGameName(), body.ccls());
    }
}
