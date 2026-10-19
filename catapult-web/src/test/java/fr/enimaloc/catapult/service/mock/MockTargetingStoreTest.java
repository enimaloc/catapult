package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockTargetingStoreTest {
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final MockTargetingStore store = new MockTargetingStore();

    @Test
    void flags_areListedInInsertionOrder_perMember() {
        assertThat(store.setFlag(MEMBER, "beta", null)).isEqualTo(204);
        assertThat(store.setFlag(MEMBER, "tier", "2")).isEqualTo(204);
        store.setFlag(OTHER, "x", "y");

        assertThat(store.flagsOf(MEMBER)).containsExactly(new MemberTargetingDto.Flag("beta", null), new MemberTargetingDto.Flag("tier", "2"));
        assertThat(store.flagsOf(UUID.randomUUID())).isEqualTo(List.of());
    }

    @Test
    void setFlag_trimsTheKey_replacesTheValue_andRefusesABlankKey() {
        store.setFlag(MEMBER, " tier ", "1");
        store.setFlag(MEMBER, "tier", "2");

        assertThat(store.flagsOf(MEMBER)).containsExactly(new MemberTargetingDto.Flag("tier", "2"));
        assertThat(store.setFlag(MEMBER, "  ", "v")).isEqualTo(400);
        assertThat(store.setFlag(MEMBER, null, "v")).isEqualTo(400);
    }

    @Test
    void deleteFlag_removesExactlyThatKey_andIsQuietForAnAbsentOne() {
        store.setFlag(MEMBER, "a/b %é", "1");
        store.setFlag(MEMBER, "a", "2");

        assertThat(store.deleteFlag(MEMBER, "a/b %é")).isEqualTo(204);
        assertThat(store.deleteFlag(MEMBER, "absent")).isEqualTo(204);

        assertThat(store.flagsOf(MEMBER)).containsExactly(new MemberTargetingDto.Flag("a", "2"));
    }
}
