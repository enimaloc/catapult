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
}
