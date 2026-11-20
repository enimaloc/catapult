package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRecapTest {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");

    private static StreamCategorySegment segment(String categoryId, String categoryName, String gameName,
                                                 Instant startedAt, Instant endedAt) {
        StreamCategorySegment segment = new StreamCategorySegment();
        segment.setStreamId("stream-1");
        segment.setCategoryId(categoryId);
        segment.setCategoryName(categoryName);
        segment.setGameName(gameName);
        segment.setStartedAt(startedAt);
        segment.setEndedAt(endedAt);
        return segment;
    }

    private static Instant at(Duration offset) {
        return START.plus(offset);
    }

    @Test
    void sumsPerPairInFirstAppearanceOrder() {
        List<StreamCategorySegment> segments = List.of(
                segment("1", "Factorio", "Factorio", at(Duration.ZERO), at(Duration.ofHours(1))),
                segment("2", "Just Chatting", null, at(Duration.ofHours(1)), at(Duration.ofHours(2))),
                segment("1", "Factorio", "Factorio", at(Duration.ofHours(2)), at(Duration.ofMinutes(210))),
                segment("3", "Games+Demos", "MOLTYN", at(Duration.ofMinutes(210)), at(Duration.ofMinutes(315))));

        SessionRecap recap = SessionRecap.of(segments, at(Duration.ofHours(6)));

        assertThat(recap.gamesText()).isEqualTo("""
                - Factorio (Factorio) (2h30)
                - Just Chatting (1h)
                - Games+Demos (MOLTYN) (1h45)""");
        assertThat(recap.lines()).extracting(SessionRecap.Line::duration)
                .containsExactly(Duration.ofMinutes(150), Duration.ofHours(1), Duration.ofMinutes(105));
        assertThat(recap.total()).isEqualTo(Duration.ofMinutes(315));
    }

    @Test
    void openSegmentCountsUntilTheGivenInstant() {
        List<StreamCategorySegment> segments = List.of(
                segment("1", "Factorio", "Factorio", at(Duration.ZERO), at(Duration.ofHours(1))),
                segment("2", "Just Chatting", null, at(Duration.ofHours(1)), null));

        SessionRecap recap = SessionRecap.of(segments, at(Duration.ofMinutes(105)));

        assertThat(recap.gamesText()).isEqualTo("- Factorio (Factorio) (1h)\n- Just Chatting (45min)");
        assertThat(recap.total()).isEqualTo(Duration.ofMinutes(105));
    }

    @Test
    void pairsUnderAMinuteAreOmitted() {
        List<StreamCategorySegment> segments = List.of(
                segment("2", "Just Chatting", null, at(Duration.ZERO), at(Duration.ofSeconds(50))),
                segment("1", "Factorio", "Factorio", at(Duration.ofSeconds(50)), at(Duration.ofMinutes(31))));

        SessionRecap recap = SessionRecap.of(segments, at(Duration.ofHours(1)));

        assertThat(recap.lines()).extracting(SessionRecap.Line::categoryName).containsExactly("Factorio");
        assertThat(recap.gamesText()).isEqualTo("- Factorio (Factorio) (30min)");
    }

    @Test
    void formatsDurations() {
        assertThat(SessionRecap.format(Duration.parse("PT2H30M"))).isEqualTo("2h30");
        assertThat(SessionRecap.format(Duration.parse("PT1H"))).isEqualTo("1h");
        assertThat(SessionRecap.format(Duration.parse("PT45M"))).isEqualTo("45min");
        assertThat(SessionRecap.format(Duration.parse("PT1H5M"))).isEqualTo("1h05");
        assertThat(SessionRecap.format(Duration.parse("PT1H4M30S"))).isEqualTo("1h05");
        assertThat(SessionRecap.format(Duration.parse("PT59M29S"))).isEqualTo("59min");
        assertThat(SessionRecap.format(Duration.parse("PT59M30S"))).isEqualTo("1h");
    }

    @Test
    void unknownCategoryIsNamed() {
        List<StreamCategorySegment> segments = List.of(
                segment(null, null, "Factorio", at(Duration.ZERO), at(Duration.ofMinutes(20))),
                segment(null, null, null, at(Duration.ofMinutes(20)), at(Duration.ofMinutes(30))));

        SessionRecap recap = SessionRecap.of(segments, at(Duration.ofHours(1)));

        assertThat(recap.gamesText())
                .isEqualTo("- Catégorie inconnue (Factorio) (20min)\n- Catégorie inconnue (10min)");
    }
}
