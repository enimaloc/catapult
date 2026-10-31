package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryServiceImpl;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulatedTwitchCategoryServiceTest {

    @TempDir
    Path dir;

    SimulationFixture fx;
    TwitchCategoryServiceImpl realCategories = mock(TwitchCategoryServiceImpl.class);
    SimulatedTwitchCategoryService service;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
        service = new SimulatedTwitchCategoryService(SimulationFixture.providerOf(realCategories), fx.runner);
    }

    @Test
    void category_search_uses_the_global_setting() {
        fx.set(fx.fictitious, SimulatedCalls.TWITCH_APP_CATEGORY_SEARCH, CallSetting.error(503));

        assertThat(service.searchCategories("music")).isEmpty();
        assertThat(fx.journal.recent(null)).singleElement()
                .satisfies(entry -> assertThat(entry.accountId()).isNull());

        fx.set(null, SimulatedCalls.TWITCH_APP_CATEGORY_SEARCH, CallSetting.ok());

        assertThat(service.searchCategories("music")).extracting(TwitchCategory::name).containsExactly("Music");
        assertThat(service.searchCategories("An Unknown Game")).extracting(TwitchCategory::name)
                .containsExactly("An Unknown Game");
        assertThat(service.searchCategories(" ")).isEmpty();
        verifyNoInteractions(realCategories);
    }

    @Test
    void real_category_search_delegates_once_the_twitch_keys_exist() {
        fx.env.setProperty("twitch.client-id", "id");
        fx.env.setProperty("twitch.client-secret", "secret");
        List<TwitchCategory> found = List.of(new TwitchCategory("26936", "Music", "box"));
        when(realCategories.searchCategories("music")).thenReturn(found);

        assertThat(service.searchCategories("music")).isEqualTo(found);

        verify(realCategories).searchCategories("music");
        // A real call is journaled as REAL
        assertThat(fx.journal.recent(null)).extracting(e -> e.key() + " " + e.mode())
                .containsExactly("twitch.app.category-search REAL");
    }
}
