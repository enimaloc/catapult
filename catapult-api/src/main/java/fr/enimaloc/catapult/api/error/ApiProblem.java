package fr.enimaloc.catapult.api.error;

import fr.enimaloc.catapult.common.error.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * An error with a stable {@link ErrorCode}: on an internal route, {@link ProblemAdvice} answers it as a problem whose
 * {@code detail} is {@code detail} and whose top-level extensions are {@code code} and {@code properties}. It is a
 * {@link ResponseStatusException} on purpose: on a frozen route the default resolver answers it as any other one.
 */
public class ApiProblem extends ResponseStatusException {

    private final ErrorCode code;

    public ApiProblem(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiProblem(ErrorCode code, String detail, Map<String, ?> properties) {
        super(HttpStatusCode.valueOf(code.status()), detail);
        this.code = code;
        properties.forEach(getBody()::setProperty);
        getBody().setProperty(ErrorCode.PROPERTY, code.code());
    }

    public ErrorCode code() {
        return code;
    }
}
