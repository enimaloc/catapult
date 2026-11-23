package fr.enimaloc.catapult.service.enrichment.blizzard;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BlizzardRegionTest {

    @Test
    void parsesOnlyTheFourRegions() {
        assertThat(BlizzardRegion.parse("EU")).contains(BlizzardRegion.EU);
        assertThat(BlizzardRegion.parse("tw")).contains(BlizzardRegion.TW);
        assertThat(BlizzardRegion.parse("cn")).isEmpty();
        assertThat(BlizzardRegion.parse("eu.api")).isEmpty();
        assertThat(BlizzardRegion.parse(null)).isEqualTo(Optional.empty());
    }

    @Test
    void sc2RegionIds() {
        assertThat(BlizzardRegion.US.sc2RegionId()).isEqualTo(1);
        assertThat(BlizzardRegion.EU.sc2RegionId()).isEqualTo(2);
        assertThat(BlizzardRegion.KR.sc2RegionId()).isEqualTo(3);
        assertThat(BlizzardRegion.TW.sc2RegionId()).isEqualTo(3);
    }

    @Test
    void hostAndNamespaces() {
        assertThat(BlizzardRegion.EU.host()).isEqualTo("https://eu.api.blizzard.com");
        assertThat(BlizzardRegion.KR.profileNamespace()).isEqualTo("profile-kr");
        assertThat(BlizzardRegion.US.dynamicNamespace()).isEqualTo("dynamic-us");
        assertThat(BlizzardRegion.TW.key()).isEqualTo("tw");
    }
}
