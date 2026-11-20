package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a member's session was made of: the time spent on each (Twitch category, detected game) pair, in the order the
 * pairs first appeared. Pairs under a minute are left out of the lines.
 *
 * @param lines one line per pair, a minute or more
 * @param total the whole time covered by the periods, short pairs included
 */
public record SessionRecap(List<Line> lines, Duration total) {

    /** Shown for a period whose Twitch category was never known, or known without a name. */
    static final String UNKNOWN_CATEGORY = "Catégorie inconnue";

    /** Time spent on one pair; {@code gameName} is null when no game was detected. */
    public record Line(String categoryName, String gameName, Duration duration) {
    }

    private record Pair(String categoryId, String gameName) {
    }

    /**
     * Sums the periods per pair; a period still running counts until {@code until}. The name shown for a pair is the
     * category name of its first period.
     */
    public static SessionRecap of(List<StreamCategorySegment> segments, Instant until) {
        Map<Pair, Line> byPair = new LinkedHashMap<>();
        Duration total = Duration.ZERO;
        for (StreamCategorySegment segment : segments) {
            Instant end = segment.getEndedAt() != null ? segment.getEndedAt() : until;
            Duration spent = Duration.between(segment.getStartedAt(), end);
            if (spent.isNegative()) {
                continue;
            }
            total = total.plus(spent);
            String categoryName = isBlank(segment.getCategoryId()) || isBlank(segment.getCategoryName())
                    ? UNKNOWN_CATEGORY : segment.getCategoryName();
            byPair.merge(new Pair(segment.getCategoryId(), segment.getGameName()),
                    new Line(categoryName, segment.getGameName(), spent),
                    (first, more) -> new Line(first.categoryName(), first.gameName(),
                            first.duration().plus(more.duration())));
        }
        List<Line> lines = byPair.values().stream()
                .filter(line -> line.duration().compareTo(Duration.ofMinutes(1)) >= 0)
                .toList();
        return new SessionRecap(lines, total);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** One line per pair, {@code - <category> (<game>) (<duration>)}, without the game part when there is none. */
    public String gamesText() {
        return lines.stream()
                .map(line -> "- " + line.categoryName()
                        + (line.gameName() == null ? "" : " (" + line.gameName() + ")")
                        + " (" + format(line.duration()) + ")")
                .collect(Collectors.joining("\n"));
    }

    /** {@code 2h30}, {@code 1h} or {@code 45min}, rounded to the nearest minute (half a minute rounds up). */
    public static String format(Duration duration) {
        long minutes = (duration.getSeconds() + 30) / 60;
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + "min";
        }
        return rest == 0 ? hours + "h" : hours + "h" + String.format("%02d", rest);
    }
}
