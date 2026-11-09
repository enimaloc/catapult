package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.CclDto;
import fr.enimaloc.catapult.common.dto.channel.TwDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockPresetsTest {

    /** The admin page only renders a checkbox per available one: any other would be dropped on its first save. */
    @Test
    void presetBindings_onlyUseTheCclsAndTwsTheAdminPageOffers() {
        var ccls = MockPresets.AVAILABLE_CCLS.stream().map(CclDto::id).toList();
        var tws = MockPresets.AVAILABLE_TWS.stream().map(TwDto::id).toList();

        for (BindingDto binding : MockPresets.BINDINGS) {
            assertThat(ccls).as(binding.sourceName()).containsAll(binding.ccls());
            assertThat(tws).as(binding.sourceName()).containsAll(binding.tws());
        }
    }
}
