package fr.enimaloc.catapult.service.drops;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DropsCatalogTest {

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private final TtvDropsClient client = mock(TtvDropsClient.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final DropsCatalog catalog = new DropsCatalog(client, clock, true, Duration.ofHours(2));

    private static DropCampaign campaign(String id, String gameId, Instant end, boolean restricted, String... allowed) {
        return new DropCampaign(id, "Campaign " + id, gameId, "Game", NOW.minusSeconds(3600), end,
                "https://example.test/" + id, null, restricted, Set.of(allowed));
    }

    private void listed(DropCampaign... campaigns) {
        when(client.activeCampaigns()).thenReturn(List.of(campaigns));
        catalog.refresh();
    }

    @Test
    void openCampaign_runsForAnyChannel() {
        listed(campaign("a", "1", NOW.plusSeconds(60), false));

        assertThat(catalog.runningFor("42", "1")).extracting(DropCampaign::id).containsExactly("a");
        assertThat(catalog.runningFor("42", "2")).isEmpty();
    }

    @Test
    void restricted_onlyForAllowedChannels() {
        listed(campaign("a", "1", NOW.plusSeconds(60), true, "42"));

        assertThat(catalog.runningFor("42", "1")).hasSize(1);
        assertThat(catalog.runningFor("43", "1")).isEmpty();
    }

    @Test
    void nullChannelId_neverForRestricted() {
        listed(campaign("a", "1", NOW.plusSeconds(60), true, "42"), campaign("b", "1", NOW.plusSeconds(60), false));

        assertThat(catalog.runningFor(null, "1")).extracting(DropCampaign::id).containsExactly("b");
    }

    @Test
    void outsideItsDates_notRunning() {
        listed(campaign("a", "1", NOW.minusSeconds(1), false));

        assertThat(catalog.runningFor("42", "1")).isEmpty();
    }

    @Test
    void failedRefresh_keepsThePreviousList() {
        listed(campaign("a", "1", NOW.plusSeconds(3600), false));
        when(client.activeCampaigns()).thenThrow(new IllegalStateException("ttvdrops down"));

        catalog.refresh();

        assertThat(catalog.runningFor("42", "1")).hasSize(1);
    }

    @Test
    void olderThanTwoHours_isEmpty() {
        listed(campaign("a", "1", NOW.plus(Duration.ofDays(5)), false));
        clock.now = NOW.plus(Duration.ofHours(2)).plusSeconds(1);

        assertThat(catalog.runningFor("42", "1")).isEmpty();
    }

    @Test
    void firstEnding_picksTheEarliestEnd() {
        listed(campaign("late", "1", NOW.plusSeconds(7200), false), campaign("soon", "1", NOW.plusSeconds(60), false),
                campaign("other", "2", NOW.plusSeconds(60), false));

        assertThat(catalog.runningFor("42", "1")).extracting(DropCampaign::id).containsExactly("soon", "late");
        assertThat(catalog.firstEndingFor("42", new ArrayList<>(List.of("1", "2", "3"))))
                .containsOnlyKeys("1", "2")
                .hasEntrySatisfying("1", c -> assertThat(c.id()).isEqualTo("soon"));
    }

    @Test
    void runningCategoryIds_forTheChannel() {
        listed(campaign("a", "1", NOW.plusSeconds(60), false), campaign("b", "2", NOW.plusSeconds(60), true, "43"),
                campaign("c", "3", NOW.minusSeconds(1), false));

        assertThat(catalog.runningCategoryIds("42")).containsExactly("1");
    }

    @Test
    void refreshOff_neverCallsTtvdrops() {
        DropsCatalog off = new DropsCatalog(client, clock, false, Duration.ofHours(2));
        when(client.activeCampaigns()).thenThrow(new AssertionError("called"));

        off.refresh();

        assertThat(off.runningFor("42", "1")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
