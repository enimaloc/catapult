package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/admin/impersonate")
@RequiredArgsConstructor
public class AdminImpersonateController {

    static final String SESSION_ORIGINAL_JWT_KEY = "originalJwt";

    private final ApiClient apiClient;

    @PostMapping
    public String impersonate(@RequestParam String username, HttpSession session) {
        @SuppressWarnings("unchecked")
        Map<String, String> response = apiClient.post(
                "/api/admin/impersonate",
                Map.of("username", username),
                Map.class);

        if (response != null && response.get("token") != null) {
            String current = (String) session.getAttribute(ApiClient.SESSION_JWT_KEY);
            session.setAttribute(SESSION_ORIGINAL_JWT_KEY, current);
            session.setAttribute(ApiClient.SESSION_JWT_KEY, response.get("token"));
            forceAuthReload(session);
            return "redirect:/channels";
        }

        log.warn("Impersonation of {} failed or returned no token", username);
        return "redirect:/admin/members?error=impersonateFailed";
    }

    @PostMapping("/exit")
    public String exitImpersonation(HttpSession session) {
        String original = (String) session.getAttribute(SESSION_ORIGINAL_JWT_KEY);
        if (original != null) {
            session.setAttribute(ApiClient.SESSION_JWT_KEY, original);
            session.removeAttribute(SESSION_ORIGINAL_JWT_KEY);
            forceAuthReload(session);
        }
        return "redirect:/admin/members";
    }

    /**
     * Spring stores the resolved {@code SecurityContext} on the {@code HttpSession}
     * across requests, so the cached admin principal would survive a JWT swap and
     * the new roles ({@code ROLE_PREVIOUS_ADMINISTRATOR}, the target user's roles)
     * would never reach the page — every following request would short-circuit
     * inside {@link JwtSessionAuthFilter} because the authentication is non-null.
     * Drop both the in-memory and session-stored contexts so the filter
     * re-validates the freshly-stored JWT on the next request.
     */
    private static void forceAuthReload(HttpSession session) {
        SecurityContextHolder.clearContext();
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }
}
