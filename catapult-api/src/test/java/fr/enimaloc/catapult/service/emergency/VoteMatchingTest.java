package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VoteMatchingTest {

    private static final UserAccount A = channel();
    private static final UserAccount B = channel();
    private static final UserAccount C = channel();

    private static UserAccount channel() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        return account;
    }

    private static EmergencyReport report(String author, UserAccount channel) {
        EmergencyReport r = new EmergencyReport();
        r.setReportedByTwitchId(author);
        r.setChannel(channel);
        return r;
    }

    @Test
    void empty_isZero() {
        assertThat(VoteMatching.maxMatching(List.of())).isZero();
    }

    @Test
    void oneAuthorOnManyChannels_countsOnce() {
        assertThat(VoteMatching.maxMatching(List.of(report("x", A), report("x", B), report("x", C)))).isEqualTo(1);
    }

    @Test
    void manyAuthorsOnOneChannel_countsOnce() {
        assertThat(VoteMatching.maxMatching(List.of(report("x", A), report("y", A), report("z", A)))).isEqualTo(1);
    }

    @Test
    void oneAuthorOnThreeChannelsPlusTwoOnOne_isTwo() {
        assertThat(VoteMatching.maxMatching(List.of(report("x", A), report("x", B), report("x", C),
                report("y", A), report("z", A)))).isEqualTo(2);
    }

    @Test
    void nonTrivialMatching_reroutesEarlierAuthors() {
        // x:{A,B} y:{A} z:{B,C} -> y=A, x=B, z=C needs x to move off A.
        assertThat(VoteMatching.maxMatching(List.of(report("x", A), report("x", B), report("y", A),
                report("z", B), report("z", C)))).isEqualTo(3);
    }
}
