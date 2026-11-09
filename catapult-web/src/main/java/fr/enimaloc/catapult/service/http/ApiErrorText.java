package fr.enimaloc.catapult.service.http;

import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * The user-facing text of a catapult-api problem: catapult-api's {@code detail} is English and never shown, so each
 * code that a server-rendered page shows has a message {@code api.error.<code>} in catapult-web's own bundles.
 */
@Component
@RequiredArgsConstructor
public class ApiErrorText {

    private final MessageSource messages;

    /** The message of {@code error}'s code in {@code locale}; empty when there is no error, no known code or no message. */
    public Optional<String> text(ApiError error, Locale locale, Object... args) {
        if (error == null || error.code() == null) {
            return Optional.empty();
        }
        String key = "api.error." + error.code().code();
        String text = messages.getMessage(key, args, null, locale);
        // A source that falls back to the key itself has no message for it.
        return text == null || text.equals(key) ? Optional.empty() : Optional.of(text);
    }
}
