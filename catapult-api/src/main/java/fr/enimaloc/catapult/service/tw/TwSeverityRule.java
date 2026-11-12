package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddSeverity;

import java.util.List;
import java.util.Locale;

/**
 * Whether a DTDD topic marked yes or mostly is severe enough for its TW mapping's least severe level
 * ({@code minSeverity}). It turns the TW on but when DTDD can tell the topic is below it: its reviewers' level, else
 * at least half of its members' level votes at or above it — only from {@code minVotes} level votes on. Anything DTDD
 * can't tell (no threshold, a topic without levels, severities not cached, a threshold not among the topic's levels,
 * too few votes) turns it on, as before the severities.
 */
public final class TwSeverityRule {

    /** Why a topic turned its TW on, or didn't ({@link #BELOW_VERIFIED}, {@link #BELOW_VOTES}). */
    public enum Reason {
        NO_THRESHOLD, NOT_CACHED, NO_LEVELS, UNKNOWN_LEVEL, TOO_FEW_VOTES, VERIFIED, VOTES, BELOW_VERIFIED, BELOW_VOTES;

        public boolean triggers() {
            return this != BELOW_VERIFIED && this != BELOW_VOTES;
        }
    }

    /**
     * The verdict: {@code level} the level that decided (the reviewers', else the members' most voted; null when none),
     * {@code votes} the level votes, {@code percentAtOrAbove} their share at or above the threshold (0 without one).
     */
    public record Verdict(Reason reason, String minSeverity, String level, int votes, int percentAtOrAbove) {
        public boolean triggers() {
            return reason.triggers();
        }
    }

    private TwSeverityRule() {
    }

    /**
     * @param severitiesKnown whether the topics' severities were cached at all (false: fetched before they were kept)
     * @param severity        the topic's, null when it has no levels
     */
    public static Verdict evaluate(String minSeverity, boolean severitiesKnown, DtddSeverity severity, int minVotes) {
        String min = minSeverity == null || minSeverity.isBlank() ? null : minSeverity.trim();
        if (min == null) return new Verdict(Reason.NO_THRESHOLD, null, null, votes(severity), 0);
        if (!severitiesKnown) return new Verdict(Reason.NOT_CACHED, min, null, 0, 0);
        if (severity == null || severity.levels() == null || severity.levels().isEmpty()) {
            return new Verdict(Reason.NO_LEVELS, min, null, 0, 0);
        }
        List<String> levels = severity.levels();
        int minIndex = indexOf(levels, min);
        if (minIndex < 0) return new Verdict(Reason.UNKNOWN_LEVEL, min, null, votes(severity), 0);

        Integer verified = severity.verified();
        if (verified != null && verified >= 1 && verified <= levels.size()) {
            String level = levels.get(verified - 1);
            return new Verdict(verified - 1 >= minIndex ? Reason.VERIFIED : Reason.BELOW_VERIFIED, min, level,
                    votes(severity), 0);
        }
        int total = votes(severity);
        String mostVoted = mostVoted(severity);
        if (total < Math.max(1, minVotes)) return new Verdict(Reason.TOO_FEW_VOTES, min, mostVoted, total, 0);
        int atOrAbove = 0;
        List<Integer> counts = severity.counts();
        for (int i = minIndex; i < counts.size(); i++) {
            atOrAbove += counts.get(i) == null ? 0 : counts.get(i);
        }
        int percent = Math.round(100f * atOrAbove / total);
        // At least half: a tie leans to the warning
        return new Verdict(2 * atOrAbove >= total ? Reason.VOTES : Reason.BELOW_VOTES, min, mostVoted, total, percent);
    }

    /** The level votes: DTDD's total, else the counts' sum. */
    private static int votes(DtddSeverity severity) {
        if (severity == null) return 0;
        if (severity.total() > 0) return severity.total();
        return severity.counts() == null ? 0
                : severity.counts().stream().mapToInt(c -> c == null ? 0 : c).sum();
    }

    /** The members' most voted level (the more severe on a tie), null without a vote. */
    private static String mostVoted(DtddSeverity severity) {
        List<Integer> counts = severity.counts();
        if (counts == null) return null;
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < Math.min(counts.size(), severity.levels().size()); i++) {
            int c = counts.get(i) == null ? 0 : counts.get(i);
            if (c > 0 && c >= bestCount) {
                best = i;
                bestCount = c;
            }
        }
        return best < 0 ? null : severity.levels().get(best);
    }

    private static int indexOf(List<String> levels, String level) {
        String wanted = level.toLowerCase(Locale.ROOT);
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i) != null && levels.get(i).toLowerCase(Locale.ROOT).equals(wanted)) return i;
        }
        return -1;
    }
}
