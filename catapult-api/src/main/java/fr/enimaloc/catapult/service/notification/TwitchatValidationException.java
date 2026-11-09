package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;

import java.util.List;

/** Payloads refused by TwitchatPayloadValidator; answered as a 400 listing every error. */
public class TwitchatValidationException extends RuntimeException {

    private final List<TwitchatValidationError> errors;

    public TwitchatValidationException(List<TwitchatValidationError> errors) {
        super(errors.size() + " invalid Twitchat payload field(s)");
        this.errors = List.copyOf(errors);
    }

    public List<TwitchatValidationError> errors() {
        return errors;
    }
}
