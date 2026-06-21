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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class PlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_.]+)(\\|([^}]*))?}");

    public static final Set<String> KNOWN_PATHS = Set.of(
        "game.name",
        "game.summary",
        "game.release_date",
        "game.store.url",
        "game.store.steam",
        "game.store.xbox",
        "game.store.battlenet",
        "game.store.official",
        "game.igdb.url",
        "game.agerating",
        "tw.active"
    );

    private final MeterRegistry meterRegistry;
    private final TwPlaceholderRegistry twPlaceholderRegistry;

    public Optional<String> resolve(String template, GameContext ctx,
                                    Map<String, String> dbFallbacks, Locale locale) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        boolean missingRequired = false;

        while (m.find()) {
            String path = m.group(1);
            String inlineFallback = m.group(3); // null if no '|'
            boolean inlinePresent = m.group(2) != null;

            String value = lookup(ctx, path, locale);
            if (path.startsWith("tw.")) {
                meterRegistry.counter("catapult.tw.placeholder.usage",
                    "path", path).increment();
            }
            String replacement;
            if (value != null && !value.isBlank()) {
                replacement = value;
            } else if (inlinePresent) {
                replacement = inlineFallback != null ? inlineFallback : "";
            } else if (dbFallbacks.containsKey(path)) {
                replacement = dbFallbacks.get(path);
            } else {
                replacement = "";
                missingRequired = true;
                meterRegistry.counter("catapult.chat.commands.placeholder.miss",
                    "path", path).increment();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);

        String result = out.toString();
        if (result.isBlank()) return Optional.empty();
        if (missingRequired) return Optional.empty();
        return Optional.of(result);
    }

    private String lookup(GameContext ctx, String path, Locale locale) {
        if (ctx == null) return null;
        return switch (path) {
            case "game.name"          -> ctx.name();
            case "game.summary"       -> ctx.summary();
            case "game.release_date"  -> ctx.releaseDate() == null ? null
                : ctx.releaseDate().format(DateTimeFormatter.ofPattern(
                    locale.getLanguage().equals("fr") ? "dd/MM/yyyy" : "MM/dd/yyyy"));
            case "game.store.url"     -> ctx.activeStoreUrl();
            case "game.store.steam"     -> ctx.stores() == null ? null : ctx.stores().get("steam");
            case "game.store.xbox"      -> ctx.stores() == null ? null : ctx.stores().get("xbox");
            case "game.store.battlenet" -> ctx.stores() == null ? null : ctx.stores().get("battlenet");
            case "game.store.official"  -> ctx.stores() == null ? null : ctx.stores().get("official");
            case "game.igdb.url"      -> ctx.igdbSlug() == null ? null
                : "https://www.igdb.com/games/" + ctx.igdbSlug();
            case "game.agerating"     -> ctx.ageRating();
            case "tw.active"          -> joinNullIfEmpty(ctx.activeTws() == null ? null
                : ctx.activeTws().stream()
                    .map(id -> ctx.twLabels() == null ? null : ctx.twLabels().get(id))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.naturalOrder())
                    .toList());
            default -> {
                if (path.startsWith("tw.") && ctx.activeTws() != null
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

    /** Renvoie les paths inconnus présents dans un template (pour validation à l'écriture). */
    public Set<String> findUnknownPaths(String template) {
        Set<String> unknown = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String path = m.group(1);
            if (KNOWN_PATHS.contains(path)) continue;
            if (path.startsWith("tw.")) {
                String slug = path.substring(3);
                if (twPlaceholderRegistry != null
                        && twPlaceholderRegistry.getKnownPaths().contains(slug)) continue;
            }
            unknown.add(path);
        }
        return unknown;
    }
}
