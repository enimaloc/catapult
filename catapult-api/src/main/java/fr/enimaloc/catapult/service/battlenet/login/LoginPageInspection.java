package fr.enimaloc.catapult.service.battlenet.login;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure helpers behind {@link PlaywrightLoginBrowser}: classifying Battle.net login URLs and describing an
 * unrecognised page for the logs. Kept free of Playwright so they can be unit-tested.
 */
final class LoginPageInspection {

    /** Where the login stands, as told by the URL path. */
    enum Step {
        /** "Security Check": pick a verification method before a code is sent. */
        CHALLENGE_CHOOSE,
        /** The security code entry page. */
        CHALLENGE_CODE,
        /** Any other page (e-mail, password, redirect...). */
        OTHER
    }

    /** A visible element of the page, as reported for diagnostics. Never carries an input's value. */
    record PageElement(String tag, @Nullable String id, @Nullable String name, @Nullable String type,
                       @Nullable String role, @Nullable String text) {
    }

    static final int MAX_TEXT = 80;

    // Verified against the live site on 2026-11-14: /login/<locale>/challenge/<uuid>[/choose].
    private static final Pattern CHALLENGE =
            Pattern.compile("/login/[A-Za-z_-]+/challenge/[0-9A-Fa-f-]{8,}(/choose)?/?$");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"']+@[^\\s@\"']+");
    private static final Set<String> VALUE_TAGS = Set.of("input", "select", "textarea");

    private LoginPageInspection() {
    }

    /** The URL without its query string and fragment: the ticket redirect carries the ticket in the query. */
    static String withoutQuery(String url) {
        int cut = url.length();
        int q = url.indexOf('?');
        if (q >= 0) cut = q;
        int f = url.indexOf('#');
        if (f >= 0 && f < cut) cut = f;
        return url.substring(0, cut);
    }

    static Step classify(String url) {
        var m = CHALLENGE.matcher(withoutQuery(url));
        if (!m.find()) return Step.OTHER;
        return m.group(1) != null ? Step.CHALLENGE_CHOOSE : Step.CHALLENGE_CODE;
    }

    /**
     * One-line description of a page the login could not make sense of: URL path, title and the visible
     * controls and alerts. Values of inputs are never included; texts are truncated and e-mails masked.
     */
    static String describe(String url, @Nullable String title, List<PageElement> elements) {
        String controls = elements.stream().map(LoginPageInspection::describe).collect(Collectors.joining(", "));
        return "url=" + withoutQuery(url) + " title=\"" + clean(title) + "\" elements=[" + controls + "]";
    }

    private static String describe(PageElement e) {
        String tag = e.tag().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(tag);
        if (e.id() != null && !e.id().isBlank()) sb.append('#').append(e.id());
        if (e.role() != null && !e.role().isBlank()) sb.append("[role=").append(e.role()).append(']');
        if (e.name() != null && !e.name().isBlank()) sb.append("[name=").append(e.name()).append(']');
        if (e.type() != null && !e.type().isBlank()) sb.append("[type=").append(e.type()).append(']');
        // Form fields only ever show their value as text: never report it.
        if (!VALUE_TAGS.contains(tag) && e.text() != null && !e.text().isBlank()) {
            sb.append(" \"").append(clean(e.text())).append('"');
        }
        return sb.toString();
    }

    private static String clean(@Nullable String text) {
        if (text == null) return "";
        String s = EMAIL.matcher(text.replaceAll("\\s+", " ").strip()).replaceAll("***");
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "...";
    }
}
