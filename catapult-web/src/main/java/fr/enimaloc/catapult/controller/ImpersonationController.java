package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.ImpersonateRequest;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * An admin logs in as a member, and comes back. Both JWTs stay in the session, never reaching the
 * browser: the admin's under {@link #ORIGINAL_JWT_KEY}, the member's (minted by catapult-api) as the
 * session's own. Every page shows a banner meanwhile (see ModelFiller#defaultAttr), whose "Back to
 * admin" posts {@code /admin/impersonate/exit}; logging out ends both.
 * <p>
 * On each swap, what catapult-web remembered for the session's former login (its navbar's channels,
 * its streams) is dropped, so that the next page is built for the new one, and the session id changes.
 */
@Slf4j
@Controller
@RequestMapping("/admin/impersonate")
@RequiredArgsConstructor
public class ImpersonationController {
    /** The admin's own JWT while they impersonate a member. */
    public static final String ORIGINAL_JWT_KEY = "originalJwt";

    /**
     * Whether {@code request}'s session is an admin acting as a member: its browser then never joins
     * the member's OBS session nor their Twitchat relay (/me/obs, /me/twitchat/events).
     */
    public static boolean impersonating(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        try {
            return session != null && session.getAttribute(ORIGINAL_JWT_KEY) != null;
        } catch (IllegalStateException e) {
            return false; // invalidated meanwhile
        }
    }

    private final ApiService apiService;
    private final ModelFiller filler;
    private final SseStreams sse;

    /**
     * 204 once the session acts as {@code username}; else catapult-api's refusal (404 unknown, 403
     * refused) or 502 when unreachable, the session unchanged.
     */
    @PostMapping
    @ResponseBody
    public ResponseEntity<Void> impersonate(@RequestBody ImpersonateRequest body, HttpServletRequest request) {
        AdminAccess.require(filler);
        if (body.username() == null || body.username().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ApiClient.Result<String> result = apiService.adminImpersonate(body.username());
        if (!result.ok() || result.body() == null) {
            log.warn("Impersonation of {} refused or failed ({})", body.username(), result.status());
            return ResponseEntity.status(result.ok() ? HttpStatus.BAD_GATEWAY.value() : result.status()).build();
        }
        HttpSession session = request.getSession();
        // Already impersonating (an owner keeps ROLE_ADMIN): the admin's own JWT stays the one to come back to.
        if (session.getAttribute(ORIGINAL_JWT_KEY) == null) {
            session.setAttribute(ORIGINAL_JWT_KEY, session.getAttribute(ApiClient.SESSION_JWT_KEY));
        }
        session.setAttribute(ApiClient.SESSION_JWT_KEY, result.body());
        swapped(request, session);
        return ResponseEntity.noContent().build();
    }

    /**
     * Back to the admin's own login, then their members tab. Without an impersonation in progress (a
     * stale tab, a second click) it only goes there: the session is left as it is, never logged out.
     */
    @PostMapping("/exit")
    public String exit(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(ORIGINAL_JWT_KEY) instanceof String original) {
            session.setAttribute(ApiClient.SESSION_JWT_KEY, original);
            session.removeAttribute(ORIGINAL_JWT_KEY);
            swapped(request, session);
        }
        return "redirect:/admin#members";
    }

    /**
     * The session now acts as another account: what was remembered for the former one is dropped and its
     * streams close on every instance (by the current id, which they are kept under), then its id changes,
     * as on a login.
     */
    private void swapped(HttpServletRequest request, HttpSession session) {
        SessionChannels.forget(session);
        sse.closeSession(session.getId());
        request.changeSessionId();
    }
}
