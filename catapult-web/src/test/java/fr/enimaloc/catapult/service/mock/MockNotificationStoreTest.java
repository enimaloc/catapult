package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockNotificationStoreTest {
    private final List<Object> events = new ArrayList<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
    private final UUID member = UUID.randomUUID();
    private final MockNotificationStore store = new MockNotificationStore(events::add, clock,
            id -> id.equals(this.member) || id.equals(this.a) || id.equals(this.b));

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private int send(UUID target) {
        return store.create("Titre", "Corps", Severity.INFO, null, null, null, target);
    }

    private List<NotificationStreamEvent> streamEvents(String name) {
        return events.stream().filter(NotificationStreamEvent.class::isInstance).map(NotificationStreamEvent.class::cast)
                .filter(e -> e.name().equals(name)).toList();
    }

    @Test
    void anAccountIsSeededWithOneWelcomeNotification() {
        var snapshot = store.snapshot(a);

        assertThat(snapshot.items()).extracting(NotificationDto::title).containsExactly("Bienvenue sur Catapult");
        assertThat(snapshot.items().get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(snapshot.unreadCount()).isEqualTo(1);
        assertThat(store.snapshot(a).items()).hasSize(1);
    }

    @Test
    void createForEveryone_reachesEveryKnownAccount_andPublishesToEach() {
        store.snapshot(a);
        store.snapshot(b);

        assertThat(send(null)).isEqualTo(201);

        assertThat(store.snapshot(a).unreadCount()).isEqualTo(2);
        assertThat(store.snapshot(b).unreadCount()).isEqualTo(2);
        assertThat(streamEvents(NotificationStreamEvent.CREATED)).extracting(NotificationStreamEvent::accountId)
                .containsExactlyInAnyOrder(a, b);
        assertThat(streamEvents(NotificationStreamEvent.CREATED).get(0).data()).isInstanceOf(NotificationDto.class);
    }

    @Test
    void createForOneAccount_reachesOnlyIt() {
        store.snapshot(a);
        store.snapshot(b);

        assertThat(send(b)).isEqualTo(201);

        assertThat(store.snapshot(a).unreadCount()).isEqualTo(1);
        assertThat(store.snapshot(b).unreadCount()).isEqualTo(2);
        assertThat(streamEvents(NotificationStreamEvent.CREATED)).extracting(NotificationStreamEvent::accountId).containsExactly(b);
    }

    @Test
    void snapshotKeepsTheTenNewest_andPagesHoldTwenty() {
        for (int i = 0; i < 24; i++) {
            store.create("n" + i, "b", Severity.INFO, null, null, null, a);
        }

        assertThat(store.snapshot(a).items()).hasSize(10);
        assertThat(store.snapshot(a).items().get(0).title()).isEqualTo("n23");
        var first = store.page(a, 0);
        assertThat(first.content()).hasSize(20);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content().get(0).title()).isEqualTo("n23");
        assertThat(store.page(a, 1).content()).hasSize(5);
    }

    @Test
    void markRead_publishesTheNewUnreadCount() {
        send(a);
        UUID id = store.snapshot(a).items().get(0).id();
        events.clear();

        assertThat(store.markRead(a, id)).isEqualTo(204);

        assertThat(store.snapshot(a).unreadCount()).isEqualTo(1);
        assertThat(streamEvents(NotificationStreamEvent.READ)).singleElement().satisfies(e -> {
            assertThat(e.accountId()).isEqualTo(a);
            assertThat(e.data()).isEqualTo(Map.of("notificationId", id.toString(), "unreadCount", 1L));
        });
        assertThat(store.markRead(a, UUID.randomUUID())).isEqualTo(404);
    }

    @Test
    void markAllRead_publishesZero() {
        send(a);
        events.clear();

        assertThat(store.markAllRead(a)).isEqualTo(204);

        assertThat(store.snapshot(a).unreadCount()).isZero();
        assertThat(streamEvents(NotificationStreamEvent.ALL_READ)).singleElement()
                .satisfies(e -> assertThat(e.data()).isEqualTo(Map.of("unreadCount", 0L)));
    }

    @Test
    void delete_removesFromEveryHolder_andPublishesToEach() {
        store.snapshot(a);
        store.snapshot(b);
        send(null);
        UUID id = store.snapshot(a).items().get(0).id();
        store.markRead(a, id);
        events.clear();

        assertThat(store.delete(id)).isEqualTo(204);

        assertThat(store.snapshot(a).items()).hasSize(1);
        assertThat(store.snapshot(b).items()).hasSize(1);
        assertThat(streamEvents(NotificationStreamEvent.DELETED)).hasSize(2);
        assertThat(streamEvents(NotificationStreamEvent.DELETED).stream().filter(e -> e.accountId().equals(a)).findFirst().orElseThrow().data())
                .isEqualTo(Map.of("notificationId", id.toString(), "unreadCount", 1L));
        assertThat(store.delete(id)).isEqualTo(404);
    }

    @Test
    void adminListing_showsWhatWasSent_newestFirst() {
        store.create("un", "**x**", Severity.WARNING, null, null, null, a);
        store.create("deux", "y", Severity.INFO, null, null, null, null);

        var page = store.adminPage(0);

        assertThat(page.content()).extracting("title").containsExactly("deux", "un");
        assertThat(page.content().get(0).audience()).isEqualTo("ALL");
        assertThat(page.content().get(1).audience()).isEqualTo("USER");
        assertThat(page.content().get(1).targetUserId()).isEqualTo(a);
        assertThat(page.content().get(1).body()).isEqualTo("**x**");
    }

    @Test
    void validationMirrorsTheApi_andPublishesNothing() {
        Instant future = clock.instant().plus(1, ChronoUnit.DAYS);
        assertThat(store.create("", "b", Severity.INFO, null, null, null, null)).isEqualTo(400);
        assertThat(store.create("t".repeat(256), "b", Severity.INFO, null, null, null, null)).isEqualTo(400);
        assertThat(store.create("t", " ", Severity.INFO, null, null, null, null)).isEqualTo(400);
        assertThat(store.create("t", "b", null, null, null, null, null)).isEqualTo(400);
        assertThat(store.create("t", "b", Severity.INFO, "https://x.test", null, null, null)).isEqualTo(400);
        assertThat(store.create("t", "b", Severity.INFO, null, "Go", null, null)).isEqualTo(400);
        assertThat(store.create("t", "b", Severity.INFO, "ftp://x.test", "Go", null, null)).isEqualTo(400);
        assertThat(store.create("t", "b", Severity.INFO, "https://x.test/" + "a".repeat(500), "Go", null, null)).isEqualTo(400);
        assertThat(store.create("t", "b", Severity.INFO, "https://x.test", "l".repeat(65), null, null)).isEqualTo(400);
        assertThat(store.create("t", "b", Severity.INFO, null, null, clock.instant().minusSeconds(5), null)).isEqualTo(400);
        assertThat(events).isEmpty();
        assertThat(store.adminPage(0).content()).isEmpty();

        assertThat(store.create("t", "b", Severity.INFO, "https://x.test", "Go", future, a)).isEqualTo(201);
        var created = store.snapshot(a).items().get(0);
        assertThat(created.ctaUrl()).isEqualTo("https://x.test");
        assertThat(created.ctaLabel()).isEqualTo("Go");
        assertThat(created.expiresAt()).isEqualTo(future);
    }

    @Test
    void expiredNotificationsAreNotListed() {
        assertThat(store.create("t", "b", Severity.INFO, null, null, clock.instant().plusSeconds(60), a)).isEqualTo(201);
        assertThat(store.snapshot(a).items()).extracting(NotificationDto::title).contains("t");

        clock.advance(Duration.ofSeconds(61));

        assertThat(store.snapshot(a).items()).extracting(NotificationDto::title).containsExactly("Bienvenue sur Catapult");
        assertThat(store.snapshot(a).unreadCount()).isEqualTo(1);
    }

    @Test
    void anUnknownTargetIsRefused_aKnownOneIsNot() {
        assertThat(send(UUID.randomUUID())).isEqualTo(400);
        assertThat(events).isEmpty();
        assertThat(store.adminPage(0).content()).isEmpty();

        assertThat(send(member)).isEqualTo(201);
        store.snapshot(b);
        assertThat(send(b)).isEqualTo(201);
    }

    @Test
    void blankCtaFieldsBecomeNull_andANegativePageIsTheFirst() {
        assertThat(store.create("t", "b", Severity.INFO, " ", "", null, a)).isEqualTo(201);

        var created = store.snapshot(a).items().get(0);
        assertThat(created.ctaUrl()).isNull();
        assertThat(created.ctaLabel()).isNull();
        assertThat(store.page(a, -3).number()).isZero();
        assertThat(store.page(a, -3).content()).hasSize(2);
    }

    @Test
    void bodyHtmlIsEscapedWithLineBreaks() {
        assertThat(store.preview("<b>a</b> & b\nc")).isEqualTo("&lt;b&gt;a&lt;/b&gt; &amp; b<br>c");
        store.create("t", "<i>\nx", Severity.INFO, null, null, null, a);
        assertThat(store.snapshot(a).items().get(0).bodyHtml()).isEqualTo("&lt;i&gt;<br>x");
    }

    @Test
    void broadcastPublishesNameAndData_andIsLimitedToTenPerMinute() {
        var alert = new BroadcastRequestDto.AlertInfo("t", "b", 0);

        for (int i = 0; i < 10; i++) {
            assertThat(store.broadcast(alert)).isEqualTo(202);
        }
        assertThat(store.broadcast(alert)).isEqualTo(429);

        assertThat(events).hasSize(10).allSatisfy(e -> assertThat(e).isEqualTo(new BroadcastStreamEvent("alert.info", alert.data())));
    }
}
