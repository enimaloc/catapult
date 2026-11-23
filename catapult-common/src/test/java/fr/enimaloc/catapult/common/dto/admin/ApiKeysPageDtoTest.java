package fr.enimaloc.catapult.common.dto.admin;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysPageDtoTest {

    private static final KeyStatus KEY = new KeyStatus("k1", "ab…yz", "owner", true, 42);

    @Test
    void itadPageKeepsItsFlagAndKeys() {
        ApiKeysPageDto dto = ApiKeysPageDto.of(new ItadKeysPageData(List.of(KEY), true));

        assertThat(dto.enabled()).isTrue();
        assertThat(dto.keys()).containsExactly(new ApiKeysPageDto.Key("k1", "ab…yz", "owner", true, 42));
    }

    @Test
    void opencriticPageKeepsItsFlagAndKeys() {
        ApiKeysPageDto dto = ApiKeysPageDto.of(new OpenCriticKeysPageData(List.of(KEY), false));

        assertThat(dto.enabled()).isFalse();
        assertThat(dto.keys()).containsExactly(new ApiKeysPageDto.Key("k1", "ab…yz", "owner", true, 42));
    }

    @Test
    void steamGridDbPageKeepsItsFlagAndKeys() {
        ApiKeysPageDto dto = ApiKeysPageDto.of(new SteamGridDbKeysPageData(List.of(KEY), true));

        assertThat(dto.enabled()).isTrue();
        assertThat(dto.keys()).hasSize(1);
    }

    @Test
    void retroAchievementsPageKeepsItsFlagAndKeys() {
        ApiKeysPageDto dto = ApiKeysPageDto.of(new RetroAchievementsKeysPageData(List.of(KEY), true));

        assertThat(dto.enabled()).isTrue();
        assertThat(dto.keys()).containsExactly(new ApiKeysPageDto.Key("k1", "ab…yz", "owner", true, 42));
        assertThat(ApiKeysPageDto.of(new RetroAchievementsKeysPageData(null, false)))
                .isEqualTo(new ApiKeysPageDto(false, List.of()));
        assertThat(ApiKeysPageDto.of(new RetroAchievementsKeysPageData(Arrays.asList(null, KEY), true)).keys())
                .hasSize(1);
    }

    @Test
    void riotPageKeepsItsFlagAndKeys() {
        ApiKeysPageDto dto = ApiKeysPageDto.of(new RiotKeysPageData(List.of(KEY), true));

        assertThat(dto.enabled()).isTrue();
        assertThat(dto.keys()).containsExactly(new ApiKeysPageDto.Key("k1", "ab…yz", "owner", true, 42));
        assertThat(ApiKeysPageDto.of(new RiotKeysPageData(null, false))).isEqualTo(new ApiKeysPageDto(false, List.of()));
        assertThat(ApiKeysPageDto.of((RiotKeysPageData) null)).isNull();
    }

    @Test
    void faceitPageKeepsItsFlagAndKeys() {
        ApiKeysPageDto dto = ApiKeysPageDto.of(new FaceitKeysPageData(List.of(KEY), true));

        assertThat(dto.enabled()).isTrue();
        assertThat(dto.keys()).containsExactly(new ApiKeysPageDto.Key("k1", "ab…yz", "owner", true, 42));
        assertThat(ApiKeysPageDto.of(new FaceitKeysPageData(null, false))).isEqualTo(new ApiKeysPageDto(false, List.of()));
        assertThat(ApiKeysPageDto.of((FaceitKeysPageData) null)).isNull();
    }

    @Test
    void anUnreachablePageGivesNull() {
        assertThat(ApiKeysPageDto.of((RetroAchievementsKeysPageData) null)).isNull();
        assertThat(ApiKeysPageDto.of((ItadKeysPageData) null)).isNull();
        assertThat(ApiKeysPageDto.of((OpenCriticKeysPageData) null)).isNull();
        assertThat(ApiKeysPageDto.of((SteamGridDbKeysPageData) null)).isNull();
    }

    @Test
    void aPageWithoutKeysGivesNoKeys() {
        assertThat(ApiKeysPageDto.of(new ItadKeysPageData(null, true))).isEqualTo(new ApiKeysPageDto(true, List.of()));
        assertThat(ApiKeysPageDto.of(new OpenCriticKeysPageData(null, false)).keys()).isEmpty();
    }

    @Test
    void nullKeysInAPageAreDropped() {
        assertThat(ApiKeysPageDto.of(new ItadKeysPageData(Arrays.asList(null, KEY), true)).keys()).hasSize(1);
        assertThat(ApiKeysPageDto.of(new OpenCriticKeysPageData(Arrays.asList(KEY, null), true)).keys()).hasSize(1);
    }
}
