package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.api.error.FrozenRoutes;
import fr.enimaloc.catapult.common.error.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lets {@code delegate} answer first (status and {@code WWW-Authenticate} as before), then, on an internal route,
 * adds a problem body whose {@code code} follows that status ({@code unauthorized} for a 401). A frozen route keeps
 * the delegate's empty answer.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String DETAIL = "Authentication required";
    static final String BAD_REQUEST_DETAIL = "Invalid request";

    private final AuthenticationEntryPoint delegate;
    private final JsonMapper json;

    /** {@code json} is the application's mapper: Boot registered the mixin that puts {@code code} at the top level. */
    public ProblemAuthenticationEntryPoint(AuthenticationEntryPoint delegate, JsonMapper json) {
        this.delegate = delegate;
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {
        delegate.commence(request, response, authException);
        // A 401 asks for credentials; any other status (the bearer handler's 400 invalid_request) is a malformed request
        writeProblem(request, response, json,
                response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED ? DETAIL : BAD_REQUEST_DETAIL);
    }

    /**
     * Writes a problem for the status the delegate set, unless the route is frozen or the delegate already
     * committed the response.
     */
    static void writeProblem(HttpServletRequest request, HttpServletResponse response, JsonMapper json,
            String detail) throws IOException {
        if (response.isCommitted() || FrozenRoutes.isFrozen(request)) {
            return;
        }
        int status = response.getStatus();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        try {
            problem.setInstance(URI.create(request.getRequestURI()));
        } catch (IllegalArgumentException ignored) {
            // A path that is not a valid URI: the problem goes without an instance
        }
        problem.setProperty(ErrorCode.PROPERTY, ErrorCode.forStatus(status).code());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
