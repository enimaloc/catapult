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
 * its streams) is dropped, so that the next page is built for the new one.
 */
@Slf4j
@Controller
@RequestMapping("/admin/impersonate")
@RequiredArgsConstructor
public class ImpersonationController {
    /** The admin's own JWT while they impersonate a member. */
    public static final String ORIGINAL_JWT_KEY = "originalJwt";

    private final ApiService apiService;
    private final ModelFiller filler;
    private final NavEventStreamController navEvents;

    /** 204 once the session acts as {@code username}; 403 when catapult-api refuses, the session unchanged. */
    @PostMapping
    @ResponseBody
    public ResponseEntity<Void> impersonate(@RequestBody ImpersonateRequest body, HttpSession session) {
        AdminAccess.require(filler);
        if (body.username() == null || body.username().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String token = apiService.adminImpersonate(body.username());
        if (token == null) {
            log.warn("Impersonation of {} refused or failed", body.username());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // Already impersonating (an owner keeps ROLE_ADMIN): the admin's own JWT stays the one to come back to.
        if (session.getAttribute(ORIGINAL_JWT_KEY) == null) {
            session.setAttribute(ORIGINAL_JWT_KEY, session.getAttribute(ApiClient.SESSION_JWT_KEY));
        }
        session.setAttribute(ApiClient.SESSION_JWT_KEY, token);
        navEvents.forget(session);
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
            navEvents.forget(session);
        }
        return "redirect:/admin#members";
    }
}
