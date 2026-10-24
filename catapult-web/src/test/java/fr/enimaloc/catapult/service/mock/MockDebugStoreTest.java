package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto;
import fr.enimaloc.catapult.service.DebugExchangeFilter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockDebugStoreTest {

    private final UUID member = UUID.randomUUID();
    private final MockDebugStore store = new MockDebugStore(Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC), member);

    private List<DebugExchangeSummaryDto> all(String direction, String outcome) {
        List<DebugExchangeSummaryDto> out = new ArrayList<>();
        DebugExchangeFilter filter = new DebugExchangeFilter(null, direction, outcome, null, null);
        for (int page = 0; ; page++) {
            var p = store.list(member, filter, page);
            out.addAll(p.content());
            if (page + 1 >= p.totalPages()) return out;
        }
    }

    @Test
    void theSeededIncomingMessage_isRecordedAsCatapultApiDoes_andCountsAsASuccess() {
        List<DebugExchangeSummaryDto> in = all("IN", null);
        assertThat(in).hasSize(1);
        DebugExchangeSummaryDto message = in.getFirst();
        assertThat(message.method()).isEqualTo("WS");
        assertThat(message.status()).isNull();
        assertThat(message.error()).isFalse();
        assertThat(all("IN", "error")).isEmpty();
        assertThat(all("IN", "success")).containsExactly(message);
    }

    @Test
    void isError_followsCatapultApisRule() {
        assertThat(MockDebugStore.isError("IN", null, null)).isFalse();
        assertThat(MockDebugStore.isError("IN", null, "invalid")).isTrue();
        assertThat(MockDebugStore.isError("OUT", null, null)).isTrue();
        assertThat(MockDebugStore.isError("OUT", 200, null)).isFalse();
        assertThat(MockDebugStore.isError("OUT", 500, null)).isTrue();
    }

    @Test
    void everySeededExchange_carriesTheErrorFlagTheRuleGives() {
        for (DebugExchangeSummaryDto e : all(null, null)) {
            var full = store.exchange(member, e.id());
            assertThat(e.error()).as(e.operation())
                    .isEqualTo(MockDebugStore.isError(full.direction(), full.status(), full.errorMessage()));
        }
    }

    private DebugExchangeFilter none() {
        return new DebugExchangeFilter(null, null, null, null, null);
    }

    @Test
    void state_isNullForAMemberNeverDebugged_andActiveForTheSeededOne() {
        assertThat(store.state(UUID.randomUUID())).isNull();
        assertThat(store.active(UUID.randomUUID())).isFalse();

        var state = store.state(member);
        assertThat(store.active(member)).isTrue();
        assertThat(state.active()).isTrue();
        assertThat(state.revealSecrets()).isFalse();
        assertThat(state.disabledAt()).isNull();
        assertThat(state.purgeAt()).isNull();
        assertThat(state.exchangeCount()).isEqualTo(6);
    }

    @Test
    void enable_isRefusedWhileActive_andStartsAnotherMemberRevealingOrNot() {
        UUID other = UUID.randomUUID();

        assertThat(store.enable(member, true)).isEqualTo(409);
        assertThat(store.enable(other, true)).isEqualTo(204);

        assertThat(store.active(other)).isTrue();
        assertThat(store.state(other).revealSecrets()).isTrue();
        assertThat(store.state(other).exchangeCount()).isZero();
    }

    @Test
    void disable_keepsTheRecording_schedulesItsPurge_andIsIdempotent() {
        assertThat(store.disable(UUID.randomUUID())).isEqualTo(404);

        assertThat(store.disable(member)).isEqualTo(204);
        var off = store.state(member);
        assertThat(off.active()).isFalse();
        assertThat(off.disabledAt()).isEqualTo(Instant.parse("2026-10-24T12:00:00Z"));
        assertThat(off.purgeAt()).isEqualTo(Instant.parse("2026-11-23T12:00:00Z"));
        assertThat(off.exchangeCount()).isEqualTo(6);

        assertThat(store.disable(member)).isEqualTo(204);
        assertThat(store.state(member)).isEqualTo(off);
        // switched on again, it is active with what was already recorded
        assertThat(store.enable(member, false)).isEqualTo(204);
        assertThat(store.active(member)).isTrue();
        assertThat(store.state(member).exchangeCount()).isEqualTo(6);
    }

    @Test
    void clear_deletesTheExchanges_butNotTheMode() {
        assertThat(store.clear(member)).isEqualTo(204);

        assertThat(store.state(member).active()).isTrue();
        assertThat(store.state(member).exchangeCount()).isZero();
        assertThat(store.list(member, none(), 0).content()).isEmpty();
        assertThat(store.services(member)).isEmpty();
    }

    @Test
    void list_pagesNewestFirst() {
        var first = store.list(member, none(), 0);
        var second = store.list(member, none(), 1);

        assertThat(first.content()).hasSize(MockDebugStore.PAGE_SIZE);
        assertThat(first.totalElements()).isEqualTo(6);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content().getFirst().service()).isEqualTo("minecraft");
        assertThat(second.content()).hasSize(1);
        assertThat(second.content().getFirst().service()).isEqualTo("twitch");
        assertThat(store.list(member, none(), 9).content()).isEmpty();
        assertThat(store.list(member, none(), -3).number()).isZero();
        assertThat(store.list(UUID.randomUUID(), none(), 0).totalElements()).isZero();
    }

    @Test
    void list_filtersByServiceDirectionOutcomeAndPeriod() {
        assertThat(store.list(member, new DebugExchangeFilter("twitch", null, null, null, null), 0).content())
                .extracting(DebugExchangeSummaryDto::service).containsOnly("twitch").hasSize(3);
        assertThat(store.list(member, new DebugExchangeFilter(" ", "", "", null, null), 0).totalElements()).isEqualTo(6);
        assertThat(store.list(member, new DebugExchangeFilter(null, "in", null, null, null), 0).totalElements()).isEqualTo(1);
        assertThat(store.list(member, new DebugExchangeFilter(null, null, "ERROR", null, null), 0).content())
                .extracting(DebugExchangeSummaryDto::error).containsOnly(true).hasSize(2);
        assertThat(store.list(member, new DebugExchangeFilter(null, null, "success", null, null), 0).totalElements()).isEqualTo(4);

        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        var period = new DebugExchangeFilter(null, null, null, now.minusSeconds(60 * 30), now.minusSeconds(60 * 10));
        assertThat(store.list(member, period, 0).content()).extracting(DebugExchangeSummaryDto::operation)
                .containsExactly("games", "getUsers");
    }

    @Test
    void exchange_isOnlyTheMembersOwn_andServicesAreSortedAndDistinct() {
        UUID id = all(null, null).getFirst().id();

        assertThat(store.exchange(member, id)).isNotNull().extracting(e -> e.id()).isEqualTo(id);
        assertThat(store.exchange(UUID.randomUUID(), id)).isNull();
        assertThat(store.exchange(member, UUID.randomUUID())).isNull();
        assertThat(store.services(member)).containsExactly("igdb", "minecraft", "steam", "twitch");
    }
}
