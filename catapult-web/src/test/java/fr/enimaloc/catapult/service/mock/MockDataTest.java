package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.ObsData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockDataTest {

    private static MockData withoutBindings() {
        return new MockData(MockData.CHANNEL_DTOS[0], List.of(), MockData.SETTINGS_DTOS[0], List.of(),
                true, true, false, false, false, false, false, true, false, true,
                new LinkStateResponse("NONE", null, null), true, new ObsData(false, "127.0.0.1", 4455, false));
    }

    @Test
    void sessionWithoutBindings_hasNoDetectedGame() {
        MockData data = withoutBindings();
        String username = MockData.CHANNEL_DTOS[0].twitchUsername();

        ChannelPageData page = data.getPage(username, null, null);

        assertThat(data.getGameDto()).isNull();
        assertThat(page.currentGame()).isNull();
        assertThat(page.bindings().content()).isEmpty();
    }

    @Test
    void sessionWithoutBindings_canStillBeGivenADetectedGame() {
        MockData data = withoutBindings();

        data.setDetectedGame("STEAM", "Valheim");

        assertThat(data.getGameDto().bindingId()).isNull();
        assertThat(data.getGameDto().sourceName()).isEqualTo("Valheim");
    }
}
