package fr.enimaloc.catapult.service.igdb;

import fr.enimaloc.catapult.common.dto.admin.IgdbGameOption;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.twitch.TwitchCclDefinitionRepository;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.simulation.SimulationGates;
import com.google.protobuf.Timestamp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import proto.Cover;
import proto.Game;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link IgdbService#searchGameOptions}: the mapping form's IGDB game search. */
class IgdbServiceGameSearchTest {

    private final IgdbClient igdb = mock(IgdbClient.class);
    private final IgdbService service = new IgdbService(igdb, mock(IgdbGameCacheRepository.class),
            mock(IgdbGameExternalIdRepository.class), mock(IgdbGameCclRepository.class),
            mock(TwitchCclDefinitionRepository.class), mock(SteamStoreService.class), mock(RestClient.class),
            new SimpleMeterRegistry(), SimulationGates.none(), mock(GameMappingRepository.class));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "clientId", "client");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        ReflectionTestUtils.setField(service, "appAccessToken", "token");
        ReflectionTestUtils.setField(service, "tokenExpiresAt", Instant.now().plusSeconds(3600));
    }

    private static Game.Builder game(long id, String name) {
        return Game.newBuilder().setId(id).setName(name);
    }

    private static Timestamp at(String instant) {
        return Timestamp.newBuilder().setSeconds(Instant.parse(instant).getEpochSecond()).build();
    }

    @Test
    void blankQueryIsEmptyWithoutCallingIgdb() {
        assertThat(service.searchGameOptions("  ")).isEmpty();
        assertThat(service.searchGameOptions(null)).isEmpty();
        verifyNoInteractions(igdb);
    }

    @Test
    void blankTokenIsEmpty() {
        ReflectionTestUtils.setField(service, "clientSecret", "");
        ReflectionTestUtils.setField(service, "appAccessToken", null);

        assertThat(service.searchGameOptions("doom")).isEmpty();
        verifyNoInteractions(igdb);
    }

    @Test
    void aClientFailureIsEmptyNotAnException() {
        when(igdb.searchByName(anyString(), anyString())).thenThrow(new IllegalStateException("igdb down"));

        assertThat(service.searchGameOptions("doom")).isEmpty();
    }

    @Test
    void gamesWithoutANameAreFilteredOut() {
        when(igdb.searchByName("doom", "token")).thenReturn(List.of(game(1, "").build(), game(2, "Doom").build()));

        assertThat(service.searchGameOptions(" doom ")).extracting(IgdbGameOption::id).containsExactly("2");
    }

    @Test
    void theReleaseYearIsInUtc() {
        when(igdb.searchByName("doom", "token")).thenReturn(List.of(
                game(1, "Last second").setFirstReleaseDate(at("2020-12-31T23:59:59Z")).build(),
                game(2, "First second").setFirstReleaseDate(at("2021-01-01T00:00:00Z")).build(),
                game(3, "Undated").build()));

        assertThat(service.searchGameOptions("doom")).extracting(IgdbGameOption::releaseYear)
                .containsExactly(2020, 2021, null);
    }

    @Test
    void theCoverUrlComesFromTheImageIdOrIsNull() {
        when(igdb.searchByName("doom", "token")).thenReturn(List.of(
                game(1, "Covered").setCover(Cover.newBuilder().setImageId("co1abc").build()).build(),
                game(2, "Bare").build()));

        assertThat(service.searchGameOptions("doom")).extracting(IgdbGameOption::coverUrl)
                .containsExactly("https://images.igdb.com/igdb/image/upload/t_cover_small/co1abc.jpg", null);
    }

    @Test
    void theFoundNamesAreRemembered() {
        when(igdb.searchByName("doom", "token")).thenReturn(List.of(game(1, "Doom").build(), game(2, "Doom II").build()));

        service.searchGameOptions("doom");

        assertThat(service.getGameCache()).containsEntry("1", "Doom").containsEntry("2", "Doom II");
    }
}
