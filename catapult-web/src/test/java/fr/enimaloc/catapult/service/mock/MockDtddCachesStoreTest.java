package fr.enimaloc.catapult.service.mock;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MockDtddCachesStoreTest {

    private final MockDtddCachesStore store =
            new MockDtddCachesStore(Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC), 2);

    @Test
    void seeded_hasEachCache_someStale_theOldestFirst() {
        var summary = store.summary();
        assertThat(summary.topics()).isEqualTo(4);
        assertThat(summary.topicsStale()).isEqualTo(2);
        assertThat(summary.gamesStale()).isEqualTo(1);
        assertThat(summary.searchesStale()).isEqualTo(1);
        assertThat(summary.quotas()).hasSize(2);

        var topics = store.topics("", 0);
        assertThat(topics.totalElements()).isEqualTo(4);
        assertThat(topics.content()).hasSize(2);
        assertThat(topics.content().getFirst().name()).isEqualTo("Stardew Valley");
        assertThat(topics.content().getFirst().stale()).isTrue();
        assertThat(store.games("halo", 0).content()).singleElement().satisfies(g -> assertThat(g.stale()).isTrue());
        assertThat(store.searches("ELDEN", 0).content()).singleElement()
                .satisfies(s -> assertThat(s.query()).isEqualTo("elden ring"));
    }

    @Test
    void refetch_makesTheEntryFresh_andAnswersLikeCatapultApi() {
        assertThat(store.refetch("topics", "21007")).isEqualTo(200);
        assertThat(store.topics("21007", 0).content().getFirst().stale()).isFalse();
        assertThat(store.refetch("searches", " HALO ")).isEqualTo(200);
        assertThat(store.refetch("topics", "999")).isEqualTo(404);
        assertThat(store.refetch("games", "halo")).isEqualTo(400);
        assertThat(store.refetch("keys", "1")).isEqualTo(404);
    }

    @Test
    void theBulkRefetch_movesOnEachTimeItIsRead_andRunsOnceAtATime() {
        assertThat(store.start().status()).isEqualTo(202);
        assertThat(store.start().status()).isEqualTo(409);

        assertThat(store.summary().job().done()).isEqualTo(1);
        assertThat(store.cancel().body().state()).isEqualTo("CANCELLED");
        assertThat(store.summary().job().state()).isEqualTo("CANCELLED");

        store.start();
        var job = store.summary().job();
        for (int i = 0; i < 20 && "RUNNING".equals(job.state()); i++) job = store.summary().job();
        assertThat(job.state()).isEqualTo("DONE");
        assertThat(job.done()).isEqualTo(job.total());
        assertThat(job.failed()).isEqualTo(1);
    }

    @Test
    void detail_givesWhatIsCachedAboutAnEntry() {
        var title = store.detail("topics", "16758");
        assertThat(title.game().name()).isEqualTo("Halo Infinite");
        assertThat(title.topics().yesTopics()).containsExactly("a dog dies");
        assertThat(title.searches()).singleElement().satisfies(s -> assertThat(s.query()).isEqualTo("halo"));

        var search = store.detail("searches", "Halo");
        assertThat(search.titles()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("Halo Infinite");
            assertThat(t.topicsCached()).isTrue();
        });

        assertThat(store.detail("topics", "999")).isNull();
        assertThat(store.detail("games", "18842")).isNull();
    }
}
