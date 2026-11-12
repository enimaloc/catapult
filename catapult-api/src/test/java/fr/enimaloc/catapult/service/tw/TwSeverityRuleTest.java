package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddSeverity;
import fr.enimaloc.catapult.service.tw.TwSeverityRule.Reason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A DTDD topic's severity against its TW mapping's least severe level. */
class TwSeverityRuleTest {

    private static final List<String> LEVELS = List.of("Minor", "Depicted", "Excessive");

    private static DtddSeverity votes(int minor, int depicted, int excessive, Integer verified) {
        return new DtddSeverity(LEVELS, List.of(minor, depicted, excessive), List.of(), minor + depicted + excessive,
                verified);
    }

    @Test
    void withoutAThreshold_itTurnsOn() {
        assertThat(TwSeverityRule.evaluate(null, true, votes(9, 0, 0, null), 3).reason()).isEqualTo(Reason.NO_THRESHOLD);
        assertThat(TwSeverityRule.evaluate(" ", true, null, 3).triggers()).isTrue();
    }

    @Test
    void whatDtddCantTell_turnsItOn() {
        assertThat(TwSeverityRule.evaluate("Depicted", false, null, 3).reason()).isEqualTo(Reason.NOT_CACHED);
        assertThat(TwSeverityRule.evaluate("Depicted", true, null, 3).reason()).isEqualTo(Reason.NO_LEVELS);
        assertThat(TwSeverityRule.evaluate("Graphic", true, votes(9, 0, 0, null), 3).reason())
                .isEqualTo(Reason.UNKNOWN_LEVEL);
        var tooFew = TwSeverityRule.evaluate("Depicted", true, votes(2, 0, 0, null), 3);
        assertThat(tooFew.reason()).isEqualTo(Reason.TOO_FEW_VOTES);
        assertThat(tooFew.triggers()).isTrue();
        assertThat(tooFew.level()).isEqualTo("Minor");
        assertThat(tooFew.votes()).isEqualTo(2);
    }

    @Test
    void theReviewersLevel_decides_whateverTheVotes() {
        var below = TwSeverityRule.evaluate("Depicted", true, votes(0, 0, 9, 1), 3);
        assertThat(below.reason()).isEqualTo(Reason.BELOW_VERIFIED);
        assertThat(below.triggers()).isFalse();
        assertThat(below.level()).isEqualTo("Minor");
        // Even from a single vote
        assertThat(TwSeverityRule.evaluate("Depicted", true, votes(1, 0, 0, 2), 3).reason()).isEqualTo(Reason.VERIFIED);
    }

    @Test
    void halfTheVotesAtOrAboveTheThreshold_turnItOn() {
        // Minor is the most voted, yet 60 % are Depicted or worse
        var spread = TwSeverityRule.evaluate("Depicted", true, votes(4, 3, 3, null), 3);
        assertThat(spread.reason()).isEqualTo(Reason.VOTES);
        assertThat(spread.percentAtOrAbove()).isEqualTo(60);
        assertThat(spread.level()).isEqualTo("Minor");
        // A tie leans to the warning
        assertThat(TwSeverityRule.evaluate("Depicted", true, votes(2, 2, 0, null), 3).triggers()).isTrue();

        var mild = TwSeverityRule.evaluate("depicted", true, votes(7, 2, 1, null), 3);
        assertThat(mild.reason()).isEqualTo(Reason.BELOW_VOTES);
        assertThat(mild.triggers()).isFalse();
        assertThat(mild.percentAtOrAbove()).isEqualTo(30);
    }

    @Test
    void theMostVotedLevel_isTheMoreSevereOnATie() {
        assertThat(TwSeverityRule.evaluate("Excessive", true, votes(3, 3, 0, null), 3).level()).isEqualTo("Depicted");
    }
}
