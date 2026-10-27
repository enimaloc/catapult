package fr.enimaloc.catapult.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lets {@code delegate} answer first (status and {@code WWW-Authenticate} as before), then, on an internal route,
 * adds a problem body whose {@code code} follows that status ({@code forbidden} for a 403). A frozen route keeps the
 * delegate's empty answer.
 */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    static final String DETAIL = "Access denied";

    private final AccessDeniedHandler delegate;
    private final JsonMapper json;

    /** {@code json} is the application's mapper: Boot registered the mixin that puts {@code code} at the top level. */
    public ProblemAccessDeniedHandler(AccessDeniedHandler delegate, JsonMapper json) {
        this.delegate = delegate;
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {
        delegate.handle(request, response, accessDeniedException);
        ProblemAuthenticationEntryPoint.writeProblem(request, response, json, DETAIL);
    }
}
