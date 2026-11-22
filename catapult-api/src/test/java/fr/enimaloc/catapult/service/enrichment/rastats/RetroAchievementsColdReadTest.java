package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.retroachievements.RetroAchievementsAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A cold read through the real limiter: the profile and the games played lately are two back-to-back calls, and the
 * second one must wait for its slot rather than be refused.
 */
class RetroAchievementsColdReadTest {

    private static final String API = "https://retroachievements.org/API/";
    private static final String ULID = "00003EMFWR7XB8SDPEHB3K56ZQ";
    private static final String KEY = "poolkey0123456789";

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/retroachievements/" + name + ".json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void profileThenRecentlyPlayedBothGoThroughFromAColdCache() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=" + ULID + "&y=" + KEY))
                .andRespond(withSuccess(fixture("profile"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API + "API_GetUserRecentlyPlayedGames.php?u=" + ULID + "&c=10&y=" + KEY))
                .andRespond(withSuccess(fixture("recently-played"), MediaType.APPLICATION_JSON));

        EnrichmentProperties properties = new EnrichmentProperties();
        RetroAchievementsRateLimiter limiter = new RetroAchievementsRateLimiter(properties);
        HttpRetroAchievementsClient client = new HttpRetroAchievementsClient(builder.build(), limiter,
                Optional.empty());

        GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
        Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        RetroAchievementsAccountRepository accounts = mock(RetroAchievementsAccountRepository.class);
        RetroAchievementsAccountService accountService = mock(RetroAchievementsAccountService.class);
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        RetroAchievementsAccount row = new RetroAchievementsAccount(user.getId());
        row.setUsername("Scott");
        row.setUserUlid(ULID);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(accountService.credentials(eq(user), any())).thenReturn(Optional.of(new RaCredentials(KEY, false)));
        RetroAchievementsStatsService service = new RetroAchievementsStatsService(
                new EnrichmentCache(repository, properties, JsonMapper.builder().build()), client, accounts,
                accountService, properties, Clock.systemUTC());

        long started = System.nanoTime();
        Optional<Integer> game = service.resolveGame(user,
                new DetectedGame("x", GameBinding.SourceType.STEAM, "Super Mario World"), "Super Mario World",
                FetchBudget.UNLIMITED);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        server.verify();
        assertThat(game).as("both calls went through and the game was found").contains(228);
        assertThat(took).as("the second call waited for its one-second slot")
                .isBetween(Duration.ofMillis(900), Duration.ofSeconds(5));
    }
}
