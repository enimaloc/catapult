package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.InvitePageData;
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

    /**
     * The new code and link, for the page to show them in place (204 when catapult-api tells none); else
     * catapult-api's status (403 without the right to invite), 502 when unreachable.
     */
    @PostMapping("/invite/regenerate")
    public ResponseEntity<InviteCode> regenerate() {
        if (!ApiClient.hasSession()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        ApiClient.Result<InvitePageData> result = apiService.regenerateInvite();
        InvitePageData page = result.body();
        if (!result.ok() || page == null || !page.canInvite()) {
            return ResponseEntity.status(result.ok() ? HttpStatus.NO_CONTENT.value() : result.status()).build();
        }
        return ResponseEntity.ok(new InviteCode(page.code(), page.inviteUrl()));
    }

    /** A regenerated invitation: its code and the join link built on it. */
    public record InviteCode(String code, String inviteUrl) {}
}
