package fr.enimaloc.catapult.monitoring;

import java.util.regex.Pattern;

/**
 * Masks the {@code token} parameter, which may carry a member's read-only personal API token
 * ({@code /api/me/**?token=…}), as {@code token=***} in a query string, a URL or any text quoting one. The one masking
 * of it for what catapult-web logs ({@link fr.enimaloc.catapult.config.AccessLogFilter}) and sends to Sentry
 * ({@link SentryConfig}).
 */
public final class TokenParameterMask {

    /** {@code token=} as a parameter of its own (not {@code widget_token=}), its value up to the next separator. */
    private static final Pattern TOKEN_PARAMETER = Pattern.compile("(?<![\\w.%-])(token=)[^&#\\s\"']*");

    private TokenParameterMask() {
    }

    /** {@code text} with every {@code token} parameter's value replaced by {@code ***}. Null stays null. */
    public static String mask(String text) {
        return text == null ? null : TOKEN_PARAMETER.matcher(text).replaceAll("$1***");
    }
}
