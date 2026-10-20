package fr.enimaloc.catapult.config;

import org.springframework.context.support.ResourceBundleMessageSource;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A message source whose messages can embed other messages as {@code #{key}} (e.g.
 * {@code hero.discover=Discover #{app.brand}}), expanded recursively in the same locale.
 * Unknown keys are left as written; a reference cycle fails after {@value #MAX_DEPTH} levels.
 */
public class RecursiveMessageSource extends ResourceBundleMessageSource {

    private static final Pattern MESSAGE_REFERENCE =
            Pattern.compile("#\\{([^}]+)}");

    private static final int MAX_DEPTH = 10;

    /**
     * Each code's {@link MessageFormat} per locale, built once as the parent caches its own (the parent
     * formats under a lock on it, so sharing one is safe); not kept when bundles are reloaded.
     */
    private final Map<Locale, Map<String, Optional<MessageFormat>>> formats = new ConcurrentHashMap<>();

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        String message = super.resolveCodeWithoutArguments(code, locale);

        if (message == null) {
            return null;
        }

        return resolveReferences(message, locale, 0);
    }

    /**
     * Expands references in the raw message before it becomes a {@link MessageFormat}: built
     * first (as the parent does), a {@code #{key}} would be parsed as an argument named "key".
     */
    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        if (getCacheMillis() >= 0) {
            return format(code, locale).orElse(null);
        }
        return formats.computeIfAbsent(locale, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(code, ignored -> format(code, locale))
                .orElse(null);
    }

    private Optional<MessageFormat> format(String code, Locale locale) {
        String message = resolveCodeWithoutArguments(code, locale);
        return message == null ? Optional.empty() : Optional.of(createMessageFormat(message, locale));
    }

    private String resolveReferences(
            String message,
            Locale locale,
            int depth
    ) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException(
                    "Maximum i18n reference depth reached while resolving: " + message
            );
        }

        Matcher matcher = MESSAGE_REFERENCE.matcher(message);

        if (!matcher.find()) {
            return message;
        }

        StringBuilder result = new StringBuilder();

        do {
            String key = matcher.group(1);

            String value = super.resolveCodeWithoutArguments(key, locale);

            if (value == null) {
                value = matcher.group(0);
            } else {
                value = resolveReferences(value, locale, depth + 1);
            }

            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(value)
            );
        } while (matcher.find());

        matcher.appendTail(result);

        return result.toString();
    }
}