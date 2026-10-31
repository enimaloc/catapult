package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.simulation.GameSourceResolver.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The id a simulated platform reports for a picked game, as Catapult resolves it. */
class GameSourceResolverTest {

    IgdbService igdb = mock(IgdbService.class);
    SimulationRegistry registry = mock(SimulationRegistry.class);
    GameSourceResolver resolver = new GameSourceResolver(igdb, registry);

    @BeforeEach
    void setUp() {
        when(registry.setting(any(), any())).thenReturn(CallSetting.real());
        when(igdb.findByName("Valheim")).thenReturn(Optional.of(new IgdbService.IgdbGame("104967", "Valheim")));
        when(igdb.findExternalUid("104967", GameBinding.SourceType.STEAM)).thenReturn(Optional.of("892970"));
        when(igdb.findExternalUid("104967", GameBinding.SourceType.XBOX)).thenReturn(Optional.of("9NBLGGH4X8M8"));
    }

    @Test
    void steam_gets_the_steam_app_id_from_igdb() {
        assertThat(resolver.resolve(GetterConfig.Provider.STEAM, "Valheim"))
                .isEqualTo(new Resolution("892970", "Valheim", null));
    }

    @Test
    void xbox_gets_the_store_product_id_from_igdb() {
        assertThat(resolver.resolve(GetterConfig.Provider.XBOX, "Valheim"))
                .isEqualTo(new Resolution("9NBLGGH4X8M8", "Valheim", null));
    }

    @Test
    void minecraft_and_battlenet_need_no_lookup() {
        assertThat(resolver.resolve(GetterConfig.Provider.MINECRAFT, "Minecraft: Java Edition"))
                .isEqualTo(new Resolution("minecraft", "Minecraft", null));
        assertThat(resolver.resolve(GetterConfig.Provider.BATTLENET, "Overwatch 2"))
                .isEqualTo(new Resolution("Overwatch 2", "Overwatch 2", null));
        verifyNoInteractions(igdb);
    }

    @Test
    void an_unknown_game_says_why_and_names_a_simulated_search() {
        when(igdb.findByName("Nope")).thenReturn(Optional.empty());
        when(registry.setting(null, SimulatedCalls.IGDB_SEARCH)).thenReturn(CallSetting.ok());

        Resolution resolution = resolver.resolve(GetterConfig.Provider.STEAM, "Nope");

        assertThat(resolution.sourceId()).isNull();
        assertThat(resolution.reason()).contains("no game named Nope").contains("igdb.search is OK");
    }

    @Test
    void a_game_without_a_store_id_says_why() {
        when(igdb.findExternalUid("104967", GameBinding.SourceType.XBOX)).thenReturn(Optional.empty());

        Resolution resolution = resolver.resolve(GetterConfig.Provider.XBOX, "Valheim");

        assertThat(resolution.sourceId()).isNull();
        assertThat(resolution.reason()).contains("no Microsoft Store product id for Valheim").doesNotContain("Real");
    }
}
