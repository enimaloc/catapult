package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.UserInfoResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DangerZoneViewTest {

    private static UserInfoResponse info(String status, String requestedAt) {
        return new UserInfoResponse(null, "1", "me", null, status, List.of(), requestedAt);
    }

    @Test
    void pending_deletesAfterTheDelay() {
        var v = DangerZoneView.of(info("PENDING_DELETION", "2026-10-01T10:00:00Z"), 7);

        assertThat(v.pending()).isTrue();
        assertThat(v.deletesAt()).isEqualTo(Instant.parse("2026-10-08T10:00:00Z"));
        assertThat(v.delayDays()).isEqualTo(7);
    }

    @Test
    void active_isNotPending() {
        var v = DangerZoneView.of(info("ACTIVE", null), 7);

        assertThat(v.pending()).isFalse();
        assertThat(v.deletesAt()).isNull();
    }

    @Test
    void pendingWithoutADate_hasNoDeletionDate() {
        assertThat(DangerZoneView.of(info("PENDING_DELETION", null), 7).deletesAt()).isNull();
    }

    @Test
    void unknownAccount_isNotPending() {
        var v = DangerZoneView.of(null, 7);

        assertThat(v.pending()).isFalse();
        assertThat(v.delayDays()).isEqualTo(7);
    }
}
