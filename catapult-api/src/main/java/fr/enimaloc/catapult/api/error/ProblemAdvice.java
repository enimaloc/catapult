package fr.enimaloc.catapult.api.error;

import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.service.minecraft.MsaAuthClient;
import fr.enimaloc.catapult.service.notification.TwitchatValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /** Refused Twitchat payloads: a 400 whose {@code errors} extension lists every one ({@code index, field, message}). */
    @ExceptionHandler(TwitchatValidationException.class)
    @SneakyThrows
    public ResponseEntity<ProblemDetail> invalidTwitchatPayloads(
            TwitchatValidationException ex, HttpServletRequest request) {
        if (FrozenRoutes.isFrozen(request)) {
            throw ex;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setProperty(ErrorCode.PROPERTY, ErrorCode.TWITCHAT_INVALID_PAYLOAD.code());
        problem.setProperty("errors", ex.errors());
        return ResponseEntity.badRequest().body(problem);
    }

    /** An expired Microsoft device code: the admin starts the flow again. */
    @ExceptionHandler(MsaAuthClient.DeviceCodeExpiredException.class)
    @SneakyThrows
    public ResponseEntity<ProblemDetail> deviceCodeExpired(
            MsaAuthClient.DeviceCodeExpiredException ex, HttpServletRequest request) {
        if (FrozenRoutes.isFrozen(request)) {
            throw ex;
        }
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The device code expired");
        problem.setProperty(ErrorCode.PROPERTY, ErrorCode.MINECRAFT_DEVICE_CODE_EXPIRED.code());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    @SneakyThrows
    public ResponseEntity<ProblemDetail> unexpected(
            Exception ex, HttpServletRequest request, HttpServletResponse response) {
        List<Throwable> chain = causeChain(ex);
        if (FrozenRoutes.isFrozen(request)
                || response.isCommitted()
                || causedBySecurityFailure(chain)
                || (!isCyclic(chain) && DisconnectedClientHelper.isClientDisconnectedException(ex))) {
            throw ex;
        }
        ResponseEntity<ProblemDetail> declared = declaredStatus(chain);
        if (declared != null) {
            return declared;
        }
        log.error("Unhandled {} on {} {}", ex.getClass().getName(), request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
        problem.setProperty(ErrorCode.PROPERTY, ErrorCode.INTERNAL_ERROR.code());
        return ResponseEntity.internalServerError().body(problem);
    }

    /**
     * The answer {@code ResponseStatusExceptionResolver} would have given, as a problem: an exception whose class
     * carries {@code @ResponseStatus}, or caused by one of those or by a {@link ResponseStatusException}. It is a
     * declared answer, not a failure, so it is not logged. {@code null} when nothing in the chain declares one.
     */
    private static @Nullable ResponseEntity<ProblemDetail> declaredStatus(List<Throwable> chain) {
        for (Throwable current : chain) {
            if (current instanceof ResponseStatusException rse) {
                ErrorCode code = rse instanceof ApiProblem problem
                        ? problem.code()
                        : ErrorCode.forStatus(rse.getStatusCode().value());
                return declared(rse.getStatusCode(), rse.getReason(), code);
            }
            ResponseStatus annotation =
                    AnnotatedElementUtils.findMergedAnnotation(current.getClass(), ResponseStatus.class);
            if (annotation != null) {
                HttpStatus status = annotation.code();
                return declared(status, annotation.reason(), ErrorCode.forStatus(status.value()));
            }
        }
        return null;
    }

    /**
     * A security failure wrapped in another exception: Spring Security's {@code ExceptionTranslationFilter} unwraps
     * causes, so it must see the original exception to answer 401 or 403.
     */
    private static boolean causedBySecurityFailure(List<Throwable> chain) {
        for (Throwable current : chain) {
            if (current instanceof AccessDeniedException || current instanceof AuthenticationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the walk stopped on a cycle. Spring's own cause walkers ({@code DisconnectedClientHelper}) never end on
     * one, and a client hang-up is never cyclic.
     */
    private static boolean isCyclic(List<Throwable> chain) {
        return chain.get(chain.size() - 1).getCause() != null;
    }

    /** The exception then its causes, each once: a cause cycle ends the walk. */
    private static List<Throwable> causeChain(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = ex; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private static ResponseEntity<ProblemDetail> declared(
            HttpStatusCode status, @Nullable String reason, ErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, englishOr(reason, status));
        problem.setProperty(ErrorCode.PROPERTY, code.code());
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * {@code reason} when it can be shown as an English detail (set, plain ASCII: the French reasons carry
     * accents), else the status's reason phrase.
     */
    private static @Nullable String englishOr(@Nullable String reason, HttpStatusCode status) {
        if (reason != null && !reason.isBlank() && StandardCharsets.US_ASCII.newEncoder().canEncode(reason)) {
            return reason;
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? null : known.getReasonPhrase();
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
