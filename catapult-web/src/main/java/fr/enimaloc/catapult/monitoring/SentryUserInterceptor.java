package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.ApiService;
import io.sentry.Sentry;
import io.sentry.protocol.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Names the member behind a request in its Sentry scope, by account id alone: enough to find their
 * account, nothing more of them reaches Sentry (no IP, no name). Set here rather than by a
 * SentryUserProvider, whose filter runs before the session's JWT can be read.
 */
@RequiredArgsConstructor
class SentryUserInterceptor implements HandlerInterceptor {
    private final ApiService apiService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID account = apiService.currentUserId();
        if (account != null) {
            User user = new User();
            user.setId(account.toString());
            Sentry.setUser(user);
        }
        return true;
    }
}
