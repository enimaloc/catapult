package fr.enimaloc.catapult.chat;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PlaceholderResolver {

    // Séparateur '#' (et non '.') pour que Twitch ne détecte pas les
    // placeholders non résolus comme des liens (ex: "game.name" ≈ domaine).
    public static final Set<String> KNOWN_PATHS = Set.of(
        "game#name",
        "game#summary",
        "game#release_date",
        "game#store#url",
        "game#store#steam",
        "game#store#xbox",
        "game#store#battlenet",
        "game#store#official",
        "game#igdb#url",
        "game#agerating",
        "game#rating",
        "game#critic_rating",
        "game#platforms",
        "tw#active"
    );

    private final MeterRegistry meterRegistry;
    private final TwPlaceholderRegistry twPlaceholderRegistry;

    /**
     * Resolves a chat command template by substituting {@code {path|fallback}}
     * placeholders. The fallback section itself may contain other placeholders
     * (e.g. {@code {tw#active|{game#agerating|none}}}) — they are resolved
     * recursively when the outer path has no value.
     *
     * <p>Returns empty when the result is blank, or when a placeholder had no
     * value AND no inline-or-DB fallback (signalling the response is so
     * incomplete the bot should stay silent rather than say "x : ").</p>
     */
    public Optional<String> resolve(String template, GameContext ctx,
                                    Map<String, String> dbFallbacks, Locale locale) {
        StringBuilder out = new StringBuilder();
        boolean[] missingRequired = {false};
        resolveInto(template, 0, template.length(), out, ctx, dbFallbacks, locale, missingRequired);

        String result = out.toString();
        if (result.isBlank() || missingRequired[0]) return Optional.empty();
        return Optional.of(result);
    }

    private void resolveInto(String template, int from, int to, StringBuilder out,
                             GameContext ctx, Map<String, String> dbFallbacks,
                             Locale locale, boolean[] missingRequired) {
        int i = from;
        while (i < to) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = findMatchingClose(template, i, to);
            if (close < 0) {
                // Unbalanced brace — copy as-is and stop trying to parse
                // anything after it as a placeholder. Mirrors lenient parsing.
                out.append(template, i, to);
                return;
            }
            int bar = indexOfTopLevelBar(template, i + 1, close);
            String path = template.substring(i + 1, bar < 0 ? close : bar);
            boolean inlinePresent = bar >= 0;
            int inlineFrom = inlinePresent ? bar + 1 : -1;

            if (!isValidPath(path)) {
                // {something not a path} → leave the whole token literal.
                out.append(template, i, close + 1);
                i = close + 1;
                continue;
            }

            if (path.startsWith("tw#")) {
                meterRegistry.counter("catapult.tw.placeholder.usage", "path", path).increment();
            }

            String value = lookup(ctx, path, locale);
            if (value != null && !value.isBlank()) {
                out.append(value);
            } else if (inlinePresent) {
                // Inline fallback can itself contain placeholders (nested).
                resolveInto(template, inlineFrom, close, out, ctx, dbFallbacks, locale, missingRequired);
            } else if (dbFallbacks.containsKey(path)) {
                out.append(dbFallbacks.get(path));
            } else {
                missingRequired[0] = true;
                meterRegistry.counter("catapult.chat.commands.placeholder.miss",
                    "path", path).increment();
            }
            i = close + 1;
        }
    }

    /** Valeur brute d'un path unique (null si absente) — utilisé par !debug. */
    public String lookupRaw(GameContext ctx, String path, Locale locale) {
        return lookup(ctx, path, locale);
    }

    private String lookup(GameContext ctx, String path, Locale locale) {
        if (ctx == null) return null;
        return switch (path) {
            case "game#name"          -> ctx.name();
            case "game#summary"       -> ctx.summary();
            case "game#release_date"  -> ctx.releaseDate() == null ? null
                : ctx.releaseDate().format(DateTimeFormatter.ofPattern(
                    locale.getLanguage().equals("fr") ? "dd/MM/yyyy" : "MM/dd/yyyy"));
            case "game#store#url"     -> ctx.activeStoreUrl();
            case "game#store#steam"     -> ctx.stores() == null ? null : ctx.stores().get("steam");
            case "game#store#xbox"      -> ctx.stores() == null ? null : ctx.stores().get("xbox");
            case "game#store#battlenet" -> ctx.stores() == null ? null : ctx.stores().get("battlenet");
            case "game#store#official"  -> ctx.stores() == null ? null : ctx.stores().get("official");
            case "game#igdb#url"      -> ctx.igdbSlug() == null ? null
                : "https://www.igdb.com/games/" + ctx.igdbSlug();
            case "game#agerating"     -> ctx.ageRating();
            case "game#rating"        -> roundedOrNull(ctx.rating());
            case "game#critic_rating" -> roundedOrNull(ctx.criticRating());
            case "game#platforms"     -> joinNullIfEmpty(ctx.platforms());
            case "tw#active"          -> joinNullIfEmpty(ctx.activeTws() == null ? null
                : ctx.activeTws().stream()
                    .map(id -> ctx.twLabels() == null ? null : ctx.twLabels().get(id))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.naturalOrder())
                    .toList());
            default -> {
                if (path.startsWith("tw#") && ctx.activeTws() != null
                        && ctx.activeTws().contains(path.substring(3))) {
                    yield ctx.twLabels() == null ? null : ctx.twLabels().get(path.substring(3));
                }
                yield null;
            }
        };
    }

    private static String joinNullIfEmpty(java.util.List<String> items) {
        if (items == null || items.isEmpty()) return null;
        return String.join(", ", items);
    }

    private static String roundedOrNull(Double value) {
        return value == null ? null : String.valueOf(Math.round(value));
    }

    /** Paths inconnus présents dans un template (pour validation à l'écriture). */
    public Set<String> findUnknownPaths(String template) {
        Set<String> unknown = new HashSet<>();
        collectUnknown(template, 0, template.length(), unknown);
        return unknown;
    }

    private void collectUnknown(String template, int from, int to, Set<String> unknown) {
        int i = from;
        while (i < to) {
            char c = template.charAt(i);
            if (c != '{') { i++; continue; }
            int close = findMatchingClose(template, i, to);
            if (close < 0) return;
            int bar = indexOfTopLevelBar(template, i + 1, close);
            String path = template.substring(i + 1, bar < 0 ? close : bar);
            if (isValidPath(path)) {
                if (!KNOWN_PATHS.contains(path)) {
                    boolean knownTw = path.startsWith("tw#")
                            && twPlaceholderRegistry != null
                            && twPlaceholderRegistry.getKnownPaths().contains(path.substring(3));
                    if (!knownTw) unknown.add(path);
                }
                if (bar >= 0) collectUnknown(template, bar + 1, close, unknown);
            }
            i = close + 1;
        }
    }

    /** Match the {@code }} for a {@code {} at {@code openIdx} accounting for nesting. */
    private static int findMatchingClose(String s, int openIdx, int to) {
        int depth = 0;
        for (int i = openIdx; i < to; i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Index of the first top-level (depth-0) {@code |} in {@code [from, to)}. */
    private static int indexOfTopLevelBar(String s, int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '|' && depth == 0) return i;
        }
        return -1;
    }

    // {else} in particular is indistinguishable from a legit simple placeholder by character
    // shape alone (4 lowercase letters, no '#') — {if ...}/{for ...}/{var ...}/{print ...} all
    // naturally fail the char-class check below because their condition/header always has a
    // space plus non-path characters (quotes, '#', operators, ...), but a bare {else} has
    // nothing else in it. Without this, findUnknownPaths flags "else" as an unknown placeholder
    // for ANY {if}...{else}...{/if} template, rejecting the save with a 400 — reproduced and
    // fixed after a save on an if/else command failed with "Unknown placeholders: else".
    private static final Set<String> STRUCTURAL_KEYWORDS = Set.of("if", "else", "for", "var", "print");

    private static boolean isValidPath(String path) {
        if (path.isEmpty() || STRUCTURAL_KEYWORDS.contains(path)) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!((c >= 'a' && c <= 'z') || c == '_' || c == '#')) return false;
        }
        return true;
    }
}
