package fr.enimaloc.catapult.service.igdb;

import com.api.igdb.apicalypse.APICalypse;
import com.api.igdb.exceptions.RequestException;
import com.api.igdb.request.IGDBWrapper;
import com.api.igdb.request.ProtoRequestKt;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import proto.AlternativeName;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** IgdbClient against a stubbed IGDB SDK: the queries it sends and how it handles failures. */
class IgdbClientTest {

    private static final Game DOOM = Game.newBuilder().setId(7).setName("Doom").build();

    private MockedStatic<ProtoRequestKt> sdk;
    private IgdbClient client;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        sdk = mockStatic(ProtoRequestKt.class);
        client = new IgdbClient();
        ReflectionTestUtils.setField(client, "clientId", "client-id");
        ReflectionTestUtils.setField(client, "apiObservations",
                new ExternalApiObservations(ObservationRegistry.create(), new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    private void gamesReturn(List<Game> games) {
        sdk.when(() -> ProtoRequestKt.games(eq(IGDBWrapper.INSTANCE), any(APICalypse.class))).thenAnswer(call -> {
            lastQuery.set(call.<APICalypse>getArgument(1).buildQuery());
            return games;
        });
    }

    private void gamesFail() {
        sdk.when(() -> ProtoRequestKt.games(any(), any())).thenThrow(mock(RequestException.class));
    }

    @Test
    void searchByName() {
        gamesReturn(List.of(DOOM));

        assertThat(client.searchByName("doom", "token")).containsExactly(DOOM);
        assertThat(lastQuery.get()).contains("search \"doom\"").contains("f id,name").contains("l 5");
    }

    @Test
    void fetchGameById_andBatch() {
        gamesReturn(List.of(DOOM));

        assertThat(client.fetchGameById("7", "id,name", "token")).containsExactly(DOOM);
        assertThat(lastQuery.get()).contains("w id=7").contains("l 1");

        assertThat(client.fetchGamesByIds(List.of("7", "8"), "id", "token")).containsExactly(DOOM);
        assertThat(lastQuery.get()).contains("w id=(7,8)").contains("l 2");
    }

    @Test
    void fetchGamesByIds_withoutIds_skipsIgdb() {
        assertThat(client.fetchGamesByIds(List.of(), "id", "token")).isEmpty();
        sdk.verifyNoInteractions();
    }

    @Test
    void fetchGameDetails_returnsTheFirstGame() {
        gamesReturn(List.of(DOOM));
        assertThat(client.fetchGameDetails("7", "token")).contains(DOOM);
        assertThat(lastQuery.get()).contains("slug").contains("w id = 7");

        gamesReturn(List.of());
        assertThat(client.fetchGameDetails("7", "token")).isEmpty();
    }

    @Test
    void fetchGamePage_sortsByRating() {
        gamesReturn(List.of(DOOM));

        assertThat(client.fetchGamePage(50, 100, "token")).containsExactly(DOOM);
        assertThat(lastQuery.get()).contains("s aggregated_rating desc").contains("l 50").contains("o 100");
    }

    @Test
    void externalGames_filteredBySourceOrNot() {
        ExternalGame external = ExternalGame.newBuilder().setUid("892970").build();
        sdk.when(() -> ProtoRequestKt.externalGames(any(), any(APICalypse.class))).thenAnswer(call -> {
            lastQuery.set(call.<APICalypse>getArgument(1).buildQuery());
            return List.of(external);
        });

        assertThat(client.findExternalGameByUid("892970", 1, "token")).containsExactly(external);
        assertThat(lastQuery.get()).contains("w external_game_source=1 & uid=\"892970\"");

        client.findExternalGameByUid("892970", -1, "token");
        assertThat(lastQuery.get()).contains("w uid=\"892970\"").doesNotContain("external_game_source");

        assertThat(client.findExternalGamesByUids(List.of("1", "2"), 1, "token")).containsExactly(external);
        assertThat(lastQuery.get()).contains("w external_game_source=1 & uid=(\"1\",\"2\")").contains("l 2");

        client.findExternalGamesByUids(List.of("1"), -1, "token");
        assertThat(lastQuery.get()).contains("w uid=(\"1\")");
    }

    @Test
    void findExternalGamesByUids_withoutUids_skipsIgdb() {
        assertThat(client.findExternalGamesByUids(List.of(), 1, "token")).isEmpty();
        sdk.verifyNoInteractions();
    }

    @Test
    void findByWindowsExecutable_normalizesTheExecutableName() {
        AlternativeName name = AlternativeName.newBuilder().setName("Doom Eternal").build();
        sdk.when(() -> ProtoRequestKt.alternativeNames(any(), any(APICalypse.class))).thenAnswer(call -> {
            lastQuery.set(call.<APICalypse>getArgument(1).buildQuery());
            return List.of(name);
        });

        assertThat(client.findByWindowsExecutable("Doom_Eternal-x64.EXE", "token")).containsExactly(name);
        assertThat(lastQuery.get()).contains("w name ~ \"Doom Eternal x64\"");

        client.findByWindowsExecutable("say\"hi.exe", "token");
        assertThat(lastQuery.get()).contains("\"say\\\"hi\"");
    }

    @Test
    void findSourcesByName() throws RequestException {
        ExternalGameSource steam = ExternalGameSource.newBuilder().setId(1).setName("Steam").build();
        sdk.when(() -> ProtoRequestKt.externalGameSources(any(), any(APICalypse.class))).thenAnswer(call -> {
            lastQuery.set(call.<APICalypse>getArgument(1).buildQuery());
            return List.of(steam);
        });

        assertThat(client.findSourcesByName("Steam", "token")).containsExactly(steam);
        assertThat(lastQuery.get()).contains("w name = \"Steam\"");
    }

    @Test
    void findSourcesByName_propagatesFailures() {
        sdk.when(() -> ProtoRequestKt.externalGameSources(any(), any())).thenThrow(mock(RequestException.class));

        assertThatThrownBy(() -> client.findSourcesByName("Steam", "token")).isInstanceOf(RequestException.class);
    }

    @Test
    void otherCalls_turnFailuresIntoNoResults() {
        gamesFail();
        sdk.when(() -> ProtoRequestKt.externalGames(any(), any())).thenThrow(mock(RequestException.class));
        sdk.when(() -> ProtoRequestKt.alternativeNames(any(), any())).thenThrow(mock(RequestException.class));

        assertThat(client.searchByName("doom", "t")).isEmpty();
        assertThat(client.fetchGameById("7", "id", "t")).isEmpty();
        assertThat(client.fetchGamesByIds(List.of("7"), "id", "t")).isEmpty();
        assertThat(client.fetchGameDetails("7", "t")).isEmpty();
        assertThat(client.fetchGamePage(1, 0, "t")).isEmpty();
        assertThat(client.findExternalGameByUid("1", 1, "t")).isEmpty();
        assertThat(client.findExternalGamesByUids(List.of("1"), 1, "t")).isEmpty();
        assertThat(client.findByWindowsExecutable("a.exe", "t")).isEmpty();
    }
}
