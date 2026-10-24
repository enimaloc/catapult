package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.config.HttpTraceLoggingInterceptor;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamGameGetter;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Steam player-summaries batch: only the debugged member's share of it is recorded, and only when one is. */
class SteamBatchDebugTest {

    private static final String URL = "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/";

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final SteamApiClient steamApi = mock(SteamApiClient.class);
    /** Stands in for the steamExecutor: every task goes through DebugContext.wrap. */
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Boolean> capturingOnWorker = new CopyOnWriteArrayList<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer steam;
    private RestClient restClient;
    private SteamGameGetter getter;

    private final UserAccount a = member("76561198000000001");
    private final UserAccount b = member("76561198000000002");
    private final UserAccount c = member("76561198000000003");

    private static UserAccount member(String steamId) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setSteamId(steamId);
        return user;
    }

    @BeforeEach
    void setUp() {
        when(registry.modeOf(any())).thenReturn(Optional.empty());
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new HttpTraceLoggingInterceptor(registry, recorder));
        steam = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        when(steamApi.getPlayerSummaries(anyCollection())).thenAnswer(inv -> {
            List<String> ids = List.copyOf(inv.getArgument(0));
            return CompletableFuture.supplyAsync(DebugContext.wrap(() -> {
                capturingOnWorker.add(DebugContext.capturing());
                restClient.get().uri(URL + "?key=SECRET&steamids=" + String.join(",", ids)).retrieve()
                        .body(String.class);
                Map<String, Optional<SteamApiClient.PlayerSummary>> result = new HashMap<>();
                ids.forEach(id -> result.put(id, Optional.empty()));
                return result;
            }), pool);
        });
        getter = new SteamGameGetter(steamApi, mock(TokenEncryptionService.class), mock(SteamStoreService.class));
        getter.setDebugRecording(new DebugRecording(registry, recorder));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private void debugged(UserAccount user) {
        when(registry.modeOf(user.getId())).thenReturn(Optional.of(new DebugMode(user.getId(), false)));
        when(registry.hasAny()).thenReturn(true);
    }

    private void respondWithAllThree() {
        steam.expect(requestTo(URL + "?key=SECRET&steamids=" + a.getSteamId() + "," + b.getSteamId() + ","
                        + c.getSteamId()))
                .andRespond(withSuccess("""
                        {"response":{"players":[
                          {"steamid":"%s","personaname":"alice"},
                          {"steamid":"%s","personaname":"bob","gameid":"570"},
                          {"steamid":"%s","personaname":"carol"}]}}
                        """.formatted(a.getSteamId(), b.getSteamId(), c.getSteamId()), MediaType.APPLICATION_JSON));
    }

    @Test
    void onlyTheDebuggedMembersShareIsRecorded() throws Exception {
        debugged(b);
        respondWithAllThree();

        getter.prefetchBatch(List.of(a, b, c)).get(5, TimeUnit.SECONDS);

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(b.getId()), eq(Direction.OUT), raw.capture(), eq("steam.get_player_summaries"),
                eq(true));
        verify(recorder, never()).record(eq(a.getId()), any(), any(), any(), anyBoolean());
        verify(recorder, never()).record(eq(c.getId()), any(), any(), any(), anyBoolean());
        assertThat(raw.getValue().uri().toString())
                .isEqualTo(URL + "?key=SECRET&steamids=" + b.getSteamId());
        JsonNode players = json.readTree(new String(raw.getValue().responseBody(), StandardCharsets.UTF_8))
                .path("response").path("players");
        assertThat(players).hasSize(1);
        assertThat(players.get(0).path("steamid").asString()).isEqualTo(b.getSteamId());
        assertThat(players.get(0).path("personaname").asString()).isEqualTo("bob");
        // The poll itself still gets the whole batch.
        assertThat(getter.getCurrentGame(a)).isEmpty();
        verify(steamApi, never()).getPlayerSummary(anyString(), any());
    }

    @Test
    void noDebuggedMemberInBatch_nothingCaptured() throws Exception {
        respondWithAllThree();

        getter.prefetchBatch(List.of(a, b, c)).get(5, TimeUnit.SECONDS);

        assertThat(capturingOnWorker).containsExactly(false);
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void anotherMemberInDebug_outsideTheBatch_nothingCaptured() throws Exception {
        UserAccount elsewhere = member("76561198000000009");
        debugged(elsewhere);
        respondWithAllThree();

        getter.prefetchBatch(List.of(a, b, c)).get(5, TimeUnit.SECONDS);

        assertThat(capturingOnWorker).containsExactly(false);
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void unparseableBatchResponse_recordsNothingForTheMember() throws Exception {
        debugged(b);
        steam.expect(requestTo(URL + "?key=SECRET&steamids=" + a.getSteamId() + "," + b.getSteamId() + ","
                        + c.getSteamId()))
                .andRespond(withSuccess("<html>" + a.getSteamId() + " " + c.getSteamId() + "</html>",
                        MediaType.TEXT_HTML));

        getter.prefetchBatch(List.of(a, b, c)).get(5, TimeUnit.SECONDS);

        assertThat(capturingOnWorker).containsExactly(true);
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean());
    }
}
