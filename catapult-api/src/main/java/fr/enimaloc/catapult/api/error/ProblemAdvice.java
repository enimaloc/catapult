package fr.enimaloc.catapult.api.error;

import fr.enimaloc.catapult.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * Answers the errors of the internal routes as RFC 9457 problems carrying a top-level {@code code}
 * ({@link ErrorCode}). On a {@linkplain FrozenRoutes frozen route} every handler rethrows, so Spring's default
 * resolvers and Boot's error page answer exactly as before. Security exceptions are always left to Spring Security.
 */
@Slf4j
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

    static final String INTERNAL_ERROR_DETAIL = "Internal error";

    /**
     * Ignored: the details are Spring's English defaults, never localised by the API's {@code MessageSource} nor
     * turned into one of its message keys.
     */
    @Override
    public void setMessageSource(MessageSource messageSource) {
        // details stay English
    }

    @Override
    @SneakyThrows
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (isFrozen(request)) {
            throw ex;
        }
        ResponseEntity<Object> answer = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (answer != null && answer.getBody() instanceof ProblemDetail problem) {
            problem.setProperty(ErrorCode.PROPERTY, codeOf(ex, answer.getStatusCode().value()).code());
        }
        return answer;
    }

    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    @SneakyThrows
    public void security(Exception ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    @SneakyThrows
    public ResponseEntity<ProblemDetail> unexpected(
            Exception ex, HttpServletRequest request, HttpServletResponse response) {
        if (FrozenRoutes.isFrozen(request)
                || response.isCommitted()
                || DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            throw ex;
        }
        log.error("Unhandled {} on {} {}", ex.getClass().getName(), request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
        problem.setProperty(ErrorCode.PROPERTY, ErrorCode.INTERNAL_ERROR.code());
        return ResponseEntity.internalServerError().body(problem);
    }

    private static ErrorCode codeOf(Exception ex, int status) {
        if (ex instanceof ApiProblem problem) {
            return problem.code();
        }
        if (ex.getClass() == ResponseStatusException.class || status != HttpStatus.BAD_REQUEST.value()) {
            return ErrorCode.forStatus(status);
        }
        return ErrorCode.INVALID_REQUEST;
    }

    private static boolean isFrozen(WebRequest request) {
        HttpServletRequest servlet =
                request instanceof NativeWebRequest nativeRequest
                        ? nativeRequest.getNativeRequest(HttpServletRequest.class)
                        : null;
        return servlet == null || FrozenRoutes.isFrozen(servlet);
    }
}
