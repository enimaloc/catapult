package fr.enimaloc.catapult.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a chat response that exceeds Twitch's 500-character per-message limit
 * into several parts, each prefixed with {@code [i/N] }. Splitting prefers
 * whitespace boundaries; a single word longer than the per-part budget is
 * cut at the character boundary as a last resort.
 *
 * <p>Pure utility — no Spring dependencies — to keep the call site in
 * {@code TwitchChatService.sendMessage} a one-liner and the logic easy to
 * unit-test in isolation.</p>
 */
public final class ChatMessageSplitter {

    /** Twitch's documented hard limit on a single chat message. */
    public static final int TWITCH_MAX_LEN = 500;

    private ChatMessageSplitter() {}

    /**
     * @return one element with the original message if it fits within
     *         {@link #TWITCH_MAX_LEN}; otherwise multiple parts each prefixed
     *         with {@code [i/N] }. Empty / null input returns an empty list.
     */
    public static List<String> split(String message) {
        if (message == null || message.isEmpty()) return List.of();
        if (message.length() <= TWITCH_MAX_LEN) return List.of(message);

        int totalParts = computeTotalParts(message.length());
        int budget = TWITCH_MAX_LEN - prefixLen(totalParts, totalParts);
        // budget is computed for the worst-case prefix (i has same digit count
        // as N), so every part — including [1/12] — fits even though earlier
        // indices have a slightly shorter prefix.

        List<String> chunks = chunkOnWordBoundary(message, budget);
        // Word-boundary chunking can yield fewer chunks than the conservative
        // totalParts estimate. Re-prefix with the actual count so users see
        // [1/3] [2/3] [3/3] rather than [1/4] [2/4] [3/4].
        int actual = chunks.size();
        List<String> out = new ArrayList<>(actual);
        for (int i = 0; i < actual; i++) {
            out.add("[" + (i + 1) + "/" + actual + "] " + chunks.get(i));
        }
        return out;
    }

    /**
     * Smallest {@code N} such that {@code ceil(len / (TWITCH_MAX_LEN - prefixLen(N,N))) <= N}.
     * Iterative — for a 50KB message the loop runs ~100 times, which is fine.
     */
    private static int computeTotalParts(int len) {
        for (int n = 2; n < 10_000; n++) {
            int budget = TWITCH_MAX_LEN - prefixLen(n, n);
            if (budget <= 0) continue;
            if ((len + budget - 1) / budget <= n) return n;
        }
        throw new IllegalArgumentException("message too long to split: " + len);
    }

    /** Length of {@code "[i/n] "} in characters. */
    private static int prefixLen(int i, int n) {
        return 1 + digits(i) + 1 + digits(n) + 1 + 1;
    }

    private static int digits(int n) {
        int d = 1;
        while (n >= 10) { n /= 10; d++; }
        return d;
    }

    private static List<String> chunkOnWordBoundary(String message, int budget) {
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        int len = message.length();
        while (pos < len) {
            int remaining = len - pos;
            if (remaining <= budget) {
                chunks.add(message.substring(pos));
                break;
            }
            int end = pos + budget;
            // Walk back to the last whitespace within [pos, end). If none, hard-cut.
            int cut = lastWhitespace(message, pos, end);
            if (cut <= pos) {
                cut = end; // single word exceeds budget — char-cut as last resort
                chunks.add(message.substring(pos, cut));
                pos = cut;
            } else {
                chunks.add(message.substring(pos, cut));
                // Skip the whitespace itself so the next part doesn't start with " ".
                pos = cut + 1;
            }
        }
        return chunks;
    }

    private static int lastWhitespace(String s, int from, int toExclusive) {
        for (int i = toExclusive - 1; i >= from; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
