package fr.enimaloc.catapult.common.dto;

import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatAction;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatNotification;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The few DTOs that carry behaviour beyond their components. */
class DtoBehaviourTest {

    @Nested
    class PagedBindingsPages {
        @ParameterizedTest(name = "page {0} of {1}: first={2} last={3}")
        @CsvSource({
                "0, 1, true, true",
                "0, 3, true, false",
                "1, 3, false, false",
                "2, 3, false, true",
                "0, 0, true, true",
        })
        void firstAndLast(int number, int totalPages, boolean first, boolean last) {
            PagedBindings page = new PagedBindings(number, totalPages, 0, List.of());

            assertThat(page.first()).isEqualTo(first);
            assertThat(page.last()).isEqualTo(last);
        }
    }

    @Test
    void queryResult_hasErrorOnlyWhenOneIsSet() {
        assertThat(new QueryResult("ok", null).hasError()).isFalse();
        assertThat(new QueryResult(null, "boom").hasError()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, false, false, false",
            "true, false, false, false, true",
            "false, true, false, false, true",
            "false, false, true, false, true",
            "false, false, false, true, true",
    })
    void providersResponse_hasAny(boolean minecraft, boolean steam, boolean xbox, boolean battlenet, boolean any) {
        assertThat(new ProvidersResponse(minecraft, steam, xbox, battlenet).hasAny()).isEqualTo(any);
    }

    @ParameterizedTest
    @CsvSource({"ACCEPTED, true", "accepted, true", "PENDING, false", "NONE, false"})
    void minecraftData_connectedOnlyOnceAccepted(String status, boolean connected) {
        assertThat(new MinecraftData(status, null, null).connected()).isEqualTo(connected);
    }

    @Test
    void minecraftData_withoutStatus_isNotConnected() {
        assertThat(new MinecraftData(null, null, null).connected()).isFalse();
    }

    @Test
    void twitchatAction_urlButton() {
        assertThat(TwitchatAction.urlButton("Open", "https://x", "primary"))
                .isEqualTo(new TwitchatAction("Open", "url", "https://x", null, "primary"));
    }

    @Test
    void twitchatNotification_withoutId_getsAFreshRandomOne() {
        var first = new TwitchatNotification("m", "s", "i", "a", List.of());
        var second = new TwitchatNotification("m", "s", "i", "a", List.of());

        assertThat(first.id()).isNotBlank().isNotEqualTo(second.id());
        assertThat(first.message()).isEqualTo("m");
    }

    @Test
    void broadcasts_publishOnTheGlobalChannel() {
        BroadcastRequestDto dto = new BroadcastRequestDto.MaintenanceCancelled(Instant.EPOCH, "r");

        assertThat(dto.redisChannel()).isEqualTo(BroadcastRequestDto.CHANNEL_GLOBAL);
        assertThat(dto.wsChannel()).isEqualTo("events.global");
    }

    @Test
    void broadcasts_dataKeepsComponentOrder() {
        Instant at = Instant.parse("2026-06-22T20:00:00Z");

        assertThat(new BroadcastRequestDto.MaintenanceScheduled(at, 15, "m").data())
                .containsExactly(entry("startsAt", at), entry("durationMinutes", 15), entry("message", "m"));
        assertThat(new BroadcastRequestDto.MaintenanceCancelled(at, "r").data())
                .containsExactly(entry("originalStartsAt", at), entry("reason", "r"));
        assertThat(new BroadcastRequestDto.VersionDeployed("1.0", true).data())
                .containsExactly(entry("version", "1.0"), entry("promptReload", true));
        assertThat(new BroadcastRequestDto.AlertInfo("t", "b", 5).data())
                .containsExactly(entry("title", "t"), entry("body", "b"), entry("ttlSeconds", 5));
        assertThat(new BroadcastRequestDto.AlertWarning("t", "b", 5).data())
                .containsExactly(entry("title", "t"), entry("body", "b"), entry("ttlSeconds", 5));
    }

    @Test
    void broadcasts_dataAcceptsNullValues() {
        assertThat(new BroadcastRequestDto.AlertInfo(null, null, 0).data()).containsEntry("title", null);
    }

    private static java.util.Map.Entry<String, Object> entry(String key, Object value) {
        return new java.util.AbstractMap.SimpleEntry<>(key, value);
    }
}
