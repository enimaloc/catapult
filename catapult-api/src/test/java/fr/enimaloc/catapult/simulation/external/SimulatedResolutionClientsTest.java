package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.getter.dtdd.RealDtddApiClient;
import fr.enimaloc.catapult.service.feedback.GitLabClient;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.steam.SteamStoreServiceImpl;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import fr.enimaloc.catapult.service.xbox.XboxStoreServiceImpl;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.twitch.SimulationFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulatedResolutionClientsTest {

    @TempDir
    Path dir;

    SimulationFixture fx;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
    }

    @Test
    void igdb_ok_never_calls_igdb() throws Exception {
        fx.env.setProperty("app.igdb.client-id", "id");
        fx.env.setProperty("twitch.client-secret", "secret");
        fx.set(null, SimulatedCalls.IGDB_SEARCH, CallSetting.ok());
        fx.set(null, SimulatedCalls.IGDB_GAME, CallSetting.ok());
        IgdbClient realIgdb = mock(IgdbClient.class);
        SimulatedIgdbClient igdb = new SimulatedIgdbClient(SimulationFixture.providerOf(realIgdb), fx.runner);

        assertThat(igdb.findSourcesByName("Steam", "token")).isEmpty();
        assertThat(igdb.findExternalGameByUid("570", 1, "token")).isEmpty();
        assertThat(igdb.findExternalGamesByUids(List.of("570"), 1, "token")).isEmpty();
        assertThat(igdb.findByWindowsExecutable("dota2.exe", "token")).isEmpty();
        assertThat(igdb.searchByName("Dota", "token")).isEmpty();
        assertThat(igdb.fetchGamesByIds(List.of("1"), "name", "token")).isEmpty();
        assertThat(igdb.fetchGameById("1", "name", "token")).isEmpty();
        assertThat(igdb.fetchGameDetails("1", "token")).isEmpty();

        verifyNoInteractions(realIgdb);
        assertThat(fx.journaledKeys()).hasSize(8);
    }

    @Test
    void igdb_is_real_by_default_once_its_keys_exist() {
        fx.env.setProperty("app.igdb.client-id", "id");
        fx.env.setProperty("twitch.client-secret", "secret");
        IgdbClient realIgdb = mock(IgdbClient.class);
        when(realIgdb.searchByName("Dota", "token")).thenReturn(List.of(proto.Game.newBuilder().setId(1).build()));
        SimulatedIgdbClient igdb = new SimulatedIgdbClient(SimulationFixture.providerOf(realIgdb), fx.runner);

        assertThat(igdb.searchByName("Dota", "token")).hasSize(1);
        verify(realIgdb).searchByName("Dota", "token");
    }

    @Test
    void store_error_matches_the_real_failure() {
        SteamStoreServiceImpl realSteamStore = mock(SteamStoreServiceImpl.class);
        XboxStoreServiceImpl realXboxStore = mock(XboxStoreServiceImpl.class);
        SteamStoreService steamStore = new SimulatedSteamStoreService(SimulationFixture.providerOf(realSteamStore),
                fx.runner);
        XboxStoreService xboxStore = new SimulatedXboxStoreService(SimulationFixture.providerOf(realXboxStore),
                fx.runner);
        fx.set(null, SimulatedCalls.STEAM_STORE_APP_DETAILS, CallSetting.error(503));
        fx.set(null, SimulatedCalls.STEAM_STORE_CCLS, CallSetting.error(429));
        fx.set(null, SimulatedCalls.STEAM_STORE_TW_SIGNALS, CallSetting.timeout());
        fx.set(null, SimulatedCalls.XBOX_STORE_PRODUCT, CallSetting.error(500));

        // the real stores log a failure and answer empty
        assertThat(steamStore.fetchData("570", Locale.ENGLISH)).isEmpty();
        assertThat(steamStore.resolveEffectiveApp("570")).isEmpty();
        assertThat(steamStore.fetchDescription("570", Locale.ENGLISH)).isEmpty();
        assertThat(steamStore.fetchIADisclosure("570", Locale.ENGLISH)).isEmpty();
        assertThat(steamStore.fetchCcls(List.of("570"))).isEmpty();
        assertThat(steamStore.fetchTwSignals(List.of("570"))).isEmpty();
        assertThat(xboxStore.fetchProduct("9NBLGGH4R315", Locale.ENGLISH)).isEmpty();

        verifyNoInteractions(realSteamStore, realXboxStore);
        assertThat(fx.journal.recent(null)).extracting(entry -> entry.outcome())
                .contains("http 503", "http 429", "timeout", "http 500");
    }

    @Test
    void stores_are_real_by_default_and_ok_when_set() {
        SteamStoreServiceImpl realSteamStore = mock(SteamStoreServiceImpl.class);
        when(realSteamStore.fetchCcls(List.of("570"))).thenReturn(Map.of("570", Set.of("Gambling")));
        SteamStoreService steamStore = new SimulatedSteamStoreService(SimulationFixture.providerOf(realSteamStore),
                fx.runner);

        assertThat(steamStore.fetchCcls(List.of("570"))).containsKey("570");

        fx.set(null, SimulatedCalls.STEAM_STORE_CCLS, CallSetting.ok());
        assertThat(steamStore.fetchCcls(List.of("730"))).isEmpty();
        verify(realSteamStore).fetchCcls(List.of("570"));
    }

    @Test
    void dtdd_timeout() {
        fx.env.setProperty("dtdd.enabled", "true");
        fx.env.setProperty("dtdd.api-keys", "key");
        RealDtddApiClient realDtdd = mock(RealDtddApiClient.class);
        SimulatedDtddApiClient dtdd = new SimulatedDtddApiClient(SimulationFixture.providerOf(realDtdd), fx.runner);
        fx.set(null, SimulatedCalls.DTDD_SEARCH, CallSetting.timeout());
        fx.set(null, SimulatedCalls.DTDD_TOPICS, CallSetting.timeout());

        // the real client turns any failure into an empty answer
        assertThat(dtdd.search("Stardew Valley")).isEmpty();
        assertThat(dtdd.fetchTopics(4521L)).isEmpty();
        assertThat(dtdd.item(4521L)).isEmpty();

        verifyNoInteractions(realDtdd);
        assertThat(fx.journal.recent(null)).extracting(entry -> entry.outcome()).containsOnly("timeout");
    }

    @Test
    void dtdd_ok_finds_nothing() {
        RealDtddApiClient realDtdd = mock(RealDtddApiClient.class);
        SimulatedDtddApiClient dtdd = new SimulatedDtddApiClient(SimulationFixture.providerOf(realDtdd), fx.runner);

        assertThat(dtdd.search("Stardew Valley")).contains(List.of());
        assertThat(dtdd.fetchTopics(4521L)).isEmpty();
        verifyNoInteractions(realDtdd);
    }

    @Test
    void gitlab_ok_makes_up_an_issue_and_error_throws_like_the_rest_client() {
        GitLabClient realGitLab = mock(GitLabClient.class);
        SimulatedGitLabClient gitLab = new SimulatedGitLabClient(SimulationFixture.providerOf(realGitLab), fx.runner,
                "https://gitlab.example");

        GitLabClient.CreatedIssue created = gitLab.createIssue("A bug", "body", List.of("bug"));
        assertThat(created.webUrl()).startsWith("https://gitlab.example/");
        GitLabClient.IssueState state = gitLab.getIssue(created.iid());
        assertThat(state.state()).isEqualTo("opened");
        // never newer than what the poller knows: no notification for a simulated issue
        assertThat(state.updatedAt()).isEqualTo(Instant.EPOCH);

        fx.set(null, SimulatedCalls.GITLAB_FEEDBACK, CallSetting.error(502));
        assertThatThrownBy(() -> gitLab.getIssue(1)).isInstanceOf(HttpServerErrorException.class);
        verifyNoInteractions(realGitLab);
    }

    @Test
    void gitlab_real_delegates_once_its_token_exists() {
        fx.env.setProperty("gitlab.token", "token");
        fx.set(null, SimulatedCalls.GITLAB_FEEDBACK, CallSetting.real());
        GitLabClient realGitLab = mock(GitLabClient.class);
        GitLabClient.IssueState state = new GitLabClient.IssueState(3, "closed", Instant.now());
        when(realGitLab.getIssue(3)).thenReturn(state);
        SimulatedGitLabClient gitLab = new SimulatedGitLabClient(SimulationFixture.providerOf(realGitLab), fx.runner,
                "https://gitlab.example");

        assertThat(gitLab.getIssue(3)).isEqualTo(state);
        // A real call is journaled as REAL
        assertThat(fx.journal.recent(null)).extracting(e -> e.key() + " " + e.mode())
                .containsExactly("gitlab.feedback REAL");
    }
}
