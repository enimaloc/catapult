package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.controller.ImpersonationController;
import fr.enimaloc.catapult.controller.SessionChannels;
import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * Opens a logged-in session for a catapult-api JWT, whatever login issued it. The session gets a new id (a
 * session id known before the login is worth nothing after it), what was kept for a former login is dropped,
 * and that login's streams close on every instance.
 */
@Component
public class SessionLogin {
    private final SseStreams sse;

    public SessionLogin(SseStreams sse) {
        this.sse = sse;
    }

    /** {@code request}'s session, logged in with {@code jwt} from now on. */
    public void establish(HttpServletRequest request, String jwt) {
        HttpSession session = request.getSession();
        if (session.getAttribute(ApiClient.SESSION_JWT_KEY) != null) sse.closeSession(session.getId());
        request.changeSessionId();
        SessionChannels.forget(session);
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        // A new login ends any impersonation: "Back to admin" mustn't restore an older admin session.
        session.removeAttribute(ImpersonationController.ORIGINAL_JWT_KEY);
    }
}
