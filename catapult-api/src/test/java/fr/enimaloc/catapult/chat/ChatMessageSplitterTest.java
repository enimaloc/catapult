package fr.enimaloc.catapult.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageSplitterTest {

    @Test
    void empty_or_null_yields_empty_list() {
        assertThat(ChatMessageSplitter.split(null)).isEmpty();
        assertThat(ChatMessageSplitter.split("")).isEmpty();
    }

    @Test
    void short_message_passes_through_without_prefix() {
        String msg = "Hello chat!";
        assertThat(ChatMessageSplitter.split(msg)).containsExactly(msg);
    }

    @Test
    void message_at_max_length_passes_through_without_prefix() {
        String msg = "x".repeat(ChatMessageSplitter.TWITCH_MAX_LEN);
        assertThat(ChatMessageSplitter.split(msg)).containsExactly(msg);
    }

    @Test
    void message_one_over_max_splits_into_two_prefixed_parts() {
        // 501 'a' chars: hard char-cut path because no whitespace at all.
        String msg = "a".repeat(ChatMessageSplitter.TWITCH_MAX_LEN + 1);
        List<String> parts = ChatMessageSplitter.split(msg);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).startsWith("[1/2] ").endsWith("a");
        assertThat(parts.get(1)).startsWith("[2/2] ").endsWith("a");
        parts.forEach(p -> assertThat(p.length()).isLessThanOrEqualTo(ChatMessageSplitter.TWITCH_MAX_LEN));
        // No data loss.
        String reassembled = parts.stream()
                .map(p -> p.substring(p.indexOf(']') + 2))
                .reduce("", String::concat);
        assertThat(reassembled).isEqualTo(msg);
    }

    @Test
    void splits_prefer_whitespace_boundary() {
        // Build a message of words separated by spaces, well over the limit.
        StringBuilder sb = new StringBuilder();
        while (sb.length() < ChatMessageSplitter.TWITCH_MAX_LEN * 2) {
            sb.append("hello ");
        }
        String msg = sb.toString().trim();
        List<String> parts = ChatMessageSplitter.split(msg);
        assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
        // Each part begins with its prefix and a non-whitespace body, and ends
        // on a word boundary (no trailing space, no broken trailing word).
        for (String p : parts) {
            assertThat(p).matches("^\\[\\d+/\\d+] hello( hello)*$");
            assertThat(p.length()).isLessThanOrEqualTo(ChatMessageSplitter.TWITCH_MAX_LEN);
        }
    }

    @Test
    void single_word_longer_than_budget_falls_back_to_char_cut_for_that_word_only() {
        // A 600-char run with no spaces should char-cut inside the run.
        String run = "z".repeat(600);
        String msg = "prefix " + run + " suffix";
        List<String> parts = ChatMessageSplitter.split(msg);
        // No data loss: stripping the [i/N] prefixes and re-joining round-trips,
        // except a single space at each word-boundary cut.
        String joined = parts.stream()
                .map(p -> p.substring(p.indexOf(']') + 2))
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        // The char-cut inside 'run' doesn't introduce a space, so total content
        // length equals original (whitespace-boundary cuts only consume the
        // already-present space).
        assertThat(joined.replace(" ", "")).isEqualTo(msg.replace(" ", ""));
        parts.forEach(p -> assertThat(p.length()).isLessThanOrEqualTo(ChatMessageSplitter.TWITCH_MAX_LEN));
    }

    @Test
    void prefix_numbering_is_one_based_and_total_matches_actual_count() {
        String msg = "lorem ipsum ".repeat(120); // ~1440 chars
        List<String> parts = ChatMessageSplitter.split(msg);
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            assertThat(parts.get(i)).startsWith("[" + (i + 1) + "/" + n + "] ");
        }
    }

    @Test
    void all_parts_respect_the_500_char_ceiling() {
        // Pathological: 100 KB of 'a's, no whitespace.
        String msg = "a".repeat(100_000);
        List<String> parts = ChatMessageSplitter.split(msg);
        parts.forEach(p -> assertThat(p.length()).isLessThanOrEqualTo(ChatMessageSplitter.TWITCH_MAX_LEN));
        // And the part count is reasonable: ceil(100000 / ~493) ≈ 203.
        assertThat(parts).hasSizeGreaterThan(100).hasSizeLessThan(300);
    }
}
