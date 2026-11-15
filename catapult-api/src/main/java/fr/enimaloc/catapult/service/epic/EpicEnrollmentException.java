package fr.enimaloc.catapult.service.epic;

import lombok.Getter;

/** An Epic link could not be created. */
@Getter
public class EpicEnrollmentException extends RuntimeException {

    public enum Reason { NO_CAPACITY, NO_ACCOUNT_AVAILABLE, ALREADY_LINKED }

    private final Reason reason;

    public EpicEnrollmentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
