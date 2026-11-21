package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant;
import fr.enimaloc.catapult.repository.battlenet.BattleNetGameVariantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The enabled Battle.net game variants, cached: reloaded every {@link #REFRESH_MS} (so the other instance sees an
 * admin's change) and right after a change made here ({@link #invalidate}). When they cannot be read, the last ones
 * read stay (none at first): the detection then names the plain game rather than failing.
 */
@Slf4j
@Service
public class BattleNetGameVariants {

    static final long REFRESH_MS = 30_000;

    private final BattleNetGameVariantRepository repository;
    private volatile List<BattleNetGameVariant> rules;

    public BattleNetGameVariants(BattleNetGameVariantRepository repository) {
        this.repository = repository;
    }

    /** The variant of {@code fourcc} whose pattern matches {@code activity}, if any. */
    public Optional<BattleNetGameVariant> resolve(String fourcc, String activity) {
        List<BattleNetGameVariant> current = rules;
        if (current == null) {
            current = load();
        }
        return match(current, fourcc, activity);
    }

    /** Makes the next {@link #resolve} read the variants again. */
    public void invalidate() {
        rules = null;
    }

    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = REFRESH_MS)
    public void refresh() {
        load();
    }

    private List<BattleNetGameVariant> load() {
        try {
            List<BattleNetGameVariant> loaded = List.copyOf(repository.findByEnabledTrue());
            rules = loaded;
            return loaded;
        } catch (RuntimeException e) {
            log.warn("Could not read the Battle.net game variants: {}", e.toString());
            List<BattleNetGameVariant> last = rules;
            return last == null ? List.of() : last;
        }
    }

    /**
     * The variant among {@code rules} for that game and activity text: the one with the longest matching pattern,
     * the oldest one on a tie. Case is ignored; a missing or blank text matches nothing.
     */
    static Optional<BattleNetGameVariant> match(List<BattleNetGameVariant> rules, String fourcc, String activity) {
        if (activity == null || activity.isBlank()) {
            return Optional.empty();
        }
        String text = activity.toLowerCase(Locale.ROOT);
        return rules.stream()
                .filter(rule -> rule.getFourcc().equals(fourcc))
                .filter(rule -> longestMatch(rule, text) > 0)
                .min(Comparator.comparingInt((BattleNetGameVariant rule) -> -longestMatch(rule, text))
                        .thenComparing(BattleNetGameVariant::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /** The length of the rule's longest pattern found in {@code text} (lower case), 0 when none is. */
    private static int longestMatch(BattleNetGameVariant rule, String text) {
        int longest = 0;
        for (String pattern : rule.getPatterns()) {
            String p = pattern.toLowerCase(Locale.ROOT);
            boolean found = switch (rule.getMatchType()) {
                case PREFIX -> text.startsWith(p);
                case CONTAINS -> text.contains(p);
            };
            if (found && !p.isEmpty()) {
                longest = Math.max(longest, p.length());
            }
        }
        return longest;
    }
}
