package fr.enimaloc.catapult.service.binding;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A refused custom binding save, with the stable {@code code} the web translates (see CustomBindingErrorDto). */
public class CustomBindingException extends ResponseStatusException {

    private final String code;

    public CustomBindingException(HttpStatus status, String code, String reason) {
        super(status, reason);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
