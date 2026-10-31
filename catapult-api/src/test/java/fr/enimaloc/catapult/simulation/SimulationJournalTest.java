package fr.enimaloc.catapult.simulation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationJournalTest {

    SimulationJournal journal = new SimulationJournal();

    private static SimulationJournal.Entry entry(UUID account, int n) {
        return new SimulationJournal.Entry(Instant.now(), account, "twitch.helix.update-channel", "call " + n,
                "OK", "ok", 0);
    }

    @Test
    void keeps_the_last_500() {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 520; i++) {
            journal.record(entry(account, i));
        }

        assertThat(journal.recent(null)).hasSize(500);
        assertThat(journal.recent(null).getFirst().request()).isEqualTo("call 20");
        assertThat(journal.recent(null).getLast().request()).isEqualTo("call 519");
    }

    @Test
    void filters_by_account() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        journal.record(entry(a, 1));
        journal.record(entry(b, 2));
        journal.record(entry(null, 3));

        assertThat(journal.recent(a)).extracting(SimulationJournal.Entry::request).containsExactly("call 1");
        assertThat(journal.recent(null)).hasSize(3);
    }

    @Test
    void subscribe_returns_an_emitter_that_receives_new_entries() {
        assertThat(journal.subscribe()).isNotNull();
        journal.record(entry(UUID.randomUUID(), 1));
    }
}
