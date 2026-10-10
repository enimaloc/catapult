package fr.enimaloc.catapult.getter.steam;

import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** RealSteamApiClient against a mocked Steam Web API: requests, parsing, key choice and 429 handling. */
class RealSteamApiClientTest {

    private static final String SUMMARIES = "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/";
    private static final String OWNED = "https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/";

    private final SteamRateLimiter rateLimiter = mock(SteamRateLimiter.class);
    private final SteamApiKeyRotator rotator = mock(SteamApiKeyRotator.class);
    private MockRestServiceServer steam;
    private RealSteamApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        steam = MockRestServiceServer.bindTo(builder).build();
        client = new RealSteamApiClient(builder.build(), rateLimiter, Runnable::run, rotator,
                new ExternalApiObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(client, "profileCacheTtl", Duration.ofMinutes(15));
        when(rotator.nextKey()).thenReturn(Optional.of("api-key"));
        when(rateLimiter.acquire(any())).thenReturn(true);
        when(rateLimiter.acquireBlocking(any())).thenReturn(true);
    }

    private void respond(String url, String json) {
        steam.expect(once(), requestTo(url)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void tooManyRequests(String url, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) headers.add("Retry-After", retryAfter);
        steam.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
    }

    private static String summary(String key, String ids) {
        return SUMMARIES + "?key=" + key + "&steamids=" + ids.replace(",", "%2C");
    }

    private static String players(String... players) {
        return "{\"response\": {\"players\": [" + String.join(",", players) + "]}}";
    }

    @Nested
    class PlayerProfile {
        @Test
        void parsesDisplayNameStatusAndGame() {
            respond(summary("api-key", "123"), players(
                    "{\"personaname\": \"MyStreamer\", \"personastate\": 1, \"gameid\": \"1091500\", \"gameextrainfo\": \"Valorant\"}"));

            SteamApiClient.PlayerSummary profile = client.getPlayerProfile("123", null).join().orElseThrow();

            assertThat(profile).isEqualTo(new SteamApiClient.PlayerSummary("1091500", "Valorant", "MyStreamer", "online"));
        }

        @Test
        void mapsEachPersonaStateToItsVocabularyWord() {
            String[] expected = {"offline", "online", "busy", "away", "snooze", "looking to trade", "looking to play", "offline"};
            for (int state = 0; state < expected.length; state++) {
                respond(summary("api-key", "123"), players("{\"personaname\": \"P\", \"personastate\": " + state + "}"));
            }
            respond(summary("api-key", "123"), players("{\"personaname\": \"P\"}"));

            for (int state = 0; state < expected.length; state++) {
                assertThat(client.getPlayerProfile("123", null).join().orElseThrow().onlineStatus())
                        .as("personastate=" + state).isEqualTo(expected[state]);
            }
            assertThat(client.getPlayerProfile("123", null).join().orElseThrow().onlineStatus()).isEqualTo("offline");
        }

        @Test
        void notPlaying_hasNoGameFields_andMissingNameIsNull() {
            respond(summary("api-key", "123"), players("{\"personastate\": 1}"));

            SteamApiClient.PlayerSummary profile = client.getPlayerProfile("123", null).join().orElseThrow();

            assertThat(profile.gameId()).isNull();
            assertThat(profile.gameName()).isNull();
            assertThat(profile.displayName()).isNull();
        }

        @Test
        void personalToken_isUsedInsteadOfThePool() {
            respond(summary("personal", "123"), players("{\"personaname\": \"P\"}"));

            assertThat(client.getPlayerProfile("123", "personal").join()).isPresent();
            verify(rotator, never()).nextKey();
        }

        @Test
        void blankPersonalToken_fallsBackToThePool() {
            respond(summary("api-key", "123"), players("{\"personaname\": \"P\"}"));

            assertThat(client.getPlayerProfile("123", " ").join()).isPresent();
        }

        @Test
        void emptyOrIncompleteResponses_areEmpty() {
            respond(summary("api-key", "1"), players());
            respond(summary("api-key", "2"), "{\"response\": {}}");
            respond(summary("api-key", "3"), "{}");

            assertThat(client.getPlayerProfile("1", null).join()).isEmpty();
            assertThat(client.getPlayerProfile("2", null).join()).isEmpty();
            assertThat(client.getPlayerProfile("3", null).join()).isEmpty();
        }

        @Test
        void noKeyOrNoRateBudget_skipsSteam() {
            when(rotator.nextKey()).thenReturn(Optional.empty());
            assertThat(client.getPlayerProfile("123", null).join()).isEmpty();

            when(rotator.nextKey()).thenReturn(Optional.of("api-key"));
            when(rateLimiter.acquire("api-key")).thenReturn(false);
            assertThat(client.getPlayerProfile("123", null).join()).isEmpty();
        }

        @Test
        void tooManyRequests_blocksThePoolKeyForRetryAfter() {
            tooManyRequests(summary("api-key", "123"), "30");

            assertThat(client.getPlayerProfile("123", null).join()).isEmpty();
            verify(rotator).onKeyRateLimited("api-key", 30);
        }

        @Test
        void tooManyRequests_onAPersonalToken_throttlesThatToken_defaulting60s() {
            tooManyRequests(summary("personal", "123"), null);
            tooManyRequests(summary("personal", "456"), "soon");

            assertThat(client.getPlayerProfile("123", "personal").join()).isEmpty();
            assertThat(client.getPlayerProfile("456", "personal").join()).isEmpty();
            verify(rateLimiter, org.mockito.Mockito.times(2)).onRateLimitResponse("personal", 60);
        }

        @Test
        void serverErrors_areEmpty() {
            steam.expect(once(), requestTo(summary("api-key", "123"))).andRespond(withServerError());

            assertThat(client.getPlayerProfile("123", null).join()).isEmpty();
        }
    }

    @Nested
    class PlayerSummary {
        @Test
        void onlyWhilePlaying() {
            respond(summary("api-key", "1"), players(
                    "{\"personaname\": \"P\", \"personastate\": 3, \"gameid\": 730, \"gameextrainfo\": \"CS2\"}"));
            respond(summary("api-key", "2"), players("{\"personaname\": \"P\", \"gameid\": 730}"));
            respond(summary("api-key", "3"), players("{\"personaname\": \"P\", \"gameextrainfo\": \"CS2\"}"));
            respond(summary("api-key", "4"), players());

            assertThat(client.getPlayerSummary("1", null).join())
                    .contains(new SteamApiClient.PlayerSummary("730", "CS2", "P", "away"));
            assertThat(client.getPlayerSummary("2", null).join()).isEmpty();
            assertThat(client.getPlayerSummary("3", null).join()).isEmpty();
            assertThat(client.getPlayerSummary("4", null).join()).isEmpty();
        }
    }

    @Nested
    class PlayerSummaries {
        @Test
        void batchesOfAHundred_withEmptyForIdlePlayersAndUnknownIds() {
            List<String> ids = IntStream.range(0, 101).mapToObj(String::valueOf).toList();
            respond(summary("api-key", String.join(",", ids.subList(0, 100))), players(
                    "{\"steamid\": \"0\", \"personaname\": \"A\", \"personastate\": 2, \"gameid\": 10, \"gameextrainfo\": \"G\"}",
                    "{\"steamid\": \"1\", \"personaname\": \"B\"}"));
            respond(summary("api-key", "100"), "{\"response\": {}}");

            Map<String, Optional<SteamApiClient.PlayerSummary>> result = client.getPlayerSummaries(ids).join();

            assertThat(result).hasSize(101);
            assertThat(result.get("0")).contains(new SteamApiClient.PlayerSummary("10", "G", "A", "busy"));
            assertThat(result.get("1")).isEmpty();
            assertThat(result.get("100")).isEmpty();
            steam.verify();
        }

        @Test
        void incompleteResponses_leaveEveryoneEmpty() {
            respond(summary("api-key", "1"), "{}");

            assertThat(client.getPlayerSummaries(List.of("1")).join()).containsEntry("1", Optional.empty());
        }

        @Test
        void noKeyOrNoRateBudget_leavesEveryoneEmpty() {
            when(rotator.nextKey()).thenReturn(Optional.empty());
            assertThat(client.getPlayerSummaries(List.of("1", "2")).join())
                    .containsOnlyKeys("1", "2").allSatisfy((id, summary) -> assertThat(summary).isEmpty());

            when(rotator.nextKey()).thenReturn(Optional.of("api-key"));
            when(rateLimiter.acquire("api-key")).thenReturn(false);
            assertThat(client.getPlayerSummaries(List.of("1")).join()).containsEntry("1", Optional.empty());
        }

        @Test
        void tooManyRequests_blocksTheKey_andOtherErrorsAreSwallowed() {
            tooManyRequests(summary("api-key", "1"), "5");
            steam.expect(once(), requestTo(summary("api-key", "2"))).andRespond(withServerError());

            assertThat(client.getPlayerSummaries(List.of("1")).join()).containsEntry("1", Optional.empty());
            assertThat(client.getPlayerSummaries(List.of("2")).join()).containsEntry("2", Optional.empty());
            verify(rotator).onKeyRateLimited("api-key", 5);
        }
    }

    @Nested
    class ProfileStatus {
        private static final String PUBLIC_ONLINE = "{\"communityvisibilitystate\": 3, \"personastate\": 1}";

        @Test
        void publicProfileWithVisibleGames_isCachedPerToken() {
            respond(summary("api-key", "123"), players(PUBLIC_ONLINE));
            respond(OWNED + "?key=api-key&steamid=123", "{\"response\": {\"game_count\": 3}}");

            assertThat(client.getProfileStatus("123", null).join())
                    .isEqualTo(new SteamApiClient.SteamProfileStatus(true, false));
            assertThat(client.getProfileStatus("123", "").join())
                    .isEqualTo(new SteamApiClient.SteamProfileStatus(true, false));
            steam.verify();
        }

        @Test
        void invalidation_forcesAFreshLookup() {
            respond(summary("api-key", "123"), players(PUBLIC_ONLINE));
            respond(OWNED + "?key=api-key&steamid=123", "{\"response\": {}}");
            respond(summary("api-key", "123"), players("{\"communityvisibilitystate\": 1}"));

            assertThat(client.getProfileStatus("123", null).join())
                    .isEqualTo(new SteamApiClient.SteamProfileStatus(false, false));
            client.invalidateProfileCache("123", null);
            assertThat(client.getProfileStatus("123", null).join())
                    .isEqualTo(new SteamApiClient.SteamProfileStatus(false, true));
            steam.verify();
        }

        @Test
        void expiredEntries_areRefetched() {
            ReflectionTestUtils.setField(client, "profileCacheTtl", Duration.ofMinutes(-1));
            respond(summary("api-key", "123"), players("{\"communityvisibilitystate\": 2, \"personastate\": 0}"));
            respond(summary("api-key", "123"), players("{\"communityvisibilitystate\": 2, \"personastate\": 0}"));

            client.getProfileStatus("123", null).join();
            client.getProfileStatus("123", null).join();
            steam.verify();
            assertThat(client.getProfileCacheTtl()).isEqualTo(Duration.ofMinutes(-1));
        }

        @Test
        void unknownPlayer_isPrivateAndOnline() {
            respond(summary("api-key", "123"), players());

            assertThat(client.getProfileStatus("123", null).join())
                    .isEqualTo(new SteamApiClient.SteamProfileStatus(false, false));
        }

        @Test
        void gameListCheck_usesThePersonalToken_andHandlesEveryFailure() {
            respond(summary("personal", "1"), players(PUBLIC_ONLINE));
            tooManyRequests(OWNED + "?key=personal&steamid=1", "7");
            respond(summary("api-key", "2"), players(PUBLIC_ONLINE));
            tooManyRequests(OWNED + "?key=api-key&steamid=2", "8");
            respond(summary("api-key", "3"), players(PUBLIC_ONLINE));
            steam.expect(once(), requestTo(OWNED + "?key=api-key&steamid=3")).andRespond(withServerError());
            respond(summary("api-key", "4"), players(PUBLIC_ONLINE));
            respond(OWNED + "?key=api-key&steamid=4", "{}");

            assertThat(client.getProfileStatus("1", "personal").join().profilePublic()).isFalse();
            for (String id : List.of("2", "3", "4")) {
                assertThat(client.getProfileStatus(id, null).join().profilePublic()).as(id).isFalse();
            }
            verify(rateLimiter).onRateLimitResponse("personal", 7);
            verify(rotator).onKeyRateLimited("api-key", 8);
        }

        @Test
        void gameListCheck_withoutKeyOrBudget_isPrivate() {
            respond(summary("api-key", "1"), players(PUBLIC_ONLINE));
            respond(summary("api-key", "2"), players(PUBLIC_ONLINE));
            when(rotator.nextKey()).thenReturn(Optional.of("api-key"), Optional.empty(),
                    Optional.of("api-key"), Optional.of("api-key"));
            when(rateLimiter.acquire("api-key")).thenReturn(true, true, false);

            assertThat(client.getProfileStatus("1", null).join().profilePublic()).isFalse();
            assertThat(client.getProfileStatus("2", null).join().profilePublic()).isFalse();
            steam.verify();
        }
    }

    @Nested
    class OwnedGames {
        private static String owned(String key, String id) {
            return OWNED + "?key=" + key + "&steamid=" + id + "&include_appinfo=1";
        }

        @Test
        void listsAppIds_skippingEntriesWithoutOne() {
            respond(owned("api-key", "1"), "{\"response\": {\"games\": [{\"appid\": 730}, {\"name\": \"?\"}, {\"appid\": 10}]}}");

            assertThat(client.getOwnedGameIds("1").join()).containsExactly("730", "10");
        }

        @Test
        void incompleteResponses_areEmpty() {
            respond(owned("api-key", "1"), "{}");
            respond(owned("api-key", "2"), "{\"response\": {}}");

            assertThat(client.getOwnedGameIds("1").join()).isEmpty();
            assertThat(client.getOwnedGameIds("2").join()).isEmpty();
        }

        @Test
        void noKeyOrNoBudget_isEmpty() {
            when(rotator.nextKey()).thenReturn(Optional.empty());
            assertThat(client.getOwnedGameIds("1").join()).isEmpty();

            when(rateLimiter.acquireBlocking("personal")).thenReturn(false);
            assertThat(client.getOwnedGameIds("1", "personal").join()).isEmpty();
        }

        @Test
        void tooManyRequests_rotatesToTheNextPoolKey() {
            when(rotator.nextKey()).thenReturn(Optional.of("k1"), Optional.of("k2"));
            tooManyRequests(owned("k1", "1"), "9");
            respond(owned("k2", "1"), "{\"response\": {\"games\": [{\"appid\": 1}]}}");

            assertThat(client.getOwnedGameIds("1").join()).containsExactly("1");
            verify(rotator).onKeyRateLimited("k1", 9);
        }

        @Test
        void tooManyRequests_givesUpOnceThePoolIsExhausted() {
            when(rotator.isAllKeysBlocked()).thenReturn(true);
            tooManyRequests(owned("api-key", "1"), "9");

            assertThat(client.getOwnedGameIds("1").join()).isEmpty();
        }

        @Test
        void tooManyRequests_onAPersonalToken_givesUpAtOnce() {
            tooManyRequests(owned("personal", "1"), "9");

            assertThat(client.getOwnedGameIds("1", "personal").join()).isEmpty();
            verify(rateLimiter).onRateLimitResponse("personal", 9);
        }

        @Test
        void otherErrors_areEmpty() {
            steam.expect(once(), requestTo(owned("api-key", "1"))).andRespond(withServerError());

            assertThat(client.getOwnedGameIds("1").join()).isEmpty();
        }
    }

    @Nested
    class Playtime {
        private static String played(String key, String id) {
            return OWNED + "?key=" + key + "&steamid=" + id + "&include_appinfo=1&include_played_free_games=1";
        }

        @Test
        void returnsTheMatchingGamesPlaytime() {
            respond(played("api-key", "1"), "{\"response\": {\"games\": ["
                    + "{\"appid\": 1091500, \"playtime_forever\": 732}, {\"appid\": 730, \"playtime_forever\": 60}]}}");

            assertThat(client.getPlaytime("1", "1091500", null).join()).contains(Duration.ofMinutes(732));
        }

        @Test
        void unownedGameOrNoPlaytime_isEmpty() {
            respond(played("api-key", "1"), "{\"response\": {\"games\": [{\"appid\": 730, \"playtime_forever\": 60}]}}");
            respond(played("api-key", "2"), "{\"response\": {\"games\": [{\"appid\": 1091500}]}}");

            assertThat(client.getPlaytime("1", "1091500", null).join()).isEmpty();
            assertThat(client.getPlaytime("2", "1091500", null).join()).isEmpty();
        }

        @Test
        void incompleteResponses_areEmpty() {
            respond(played("api-key", "1"), "{}");
            respond(played("api-key", "2"), "{\"response\": {}}");

            assertThat(client.getPlaytime("1", "730", null).join()).isEmpty();
            assertThat(client.getPlaytime("2", "730", null).join()).isEmpty();
        }

        @Test
        void noKeyOrNoBudget_isEmpty() {
            when(rotator.nextKey()).thenReturn(Optional.empty());
            assertThat(client.getPlaytime("1", "730", null).join()).isEmpty();

            when(rateLimiter.acquireBlocking("personal")).thenReturn(false);
            assertThat(client.getPlaytime("1", "730", "personal").join()).isEmpty();
        }

        @Test
        void tooManyRequests_rotatesKeys_untilThePoolIsExhausted() {
            List<String> keys = new ArrayList<>(List.of("k1", "k2"));
            when(rotator.nextKey()).thenAnswer(call -> Optional.of(keys.removeFirst()));
            when(rotator.isAllKeysBlocked()).thenReturn(false, true);
            tooManyRequests(played("k1", "1"), "1");
            tooManyRequests(played("k2", "1"), "2");

            assertThat(client.getPlaytime("1", "730", null).join()).isEmpty();
            verify(rotator).onKeyRateLimited("k1", 1);
            verify(rotator).onKeyRateLimited("k2", 2);
        }

        @Test
        void tooManyRequests_onAPersonalToken_givesUpAtOnce() {
            tooManyRequests(played("personal", "1"), "4");

            assertThat(client.getPlaytime("1", "730", "personal").join()).isEmpty();
            verify(rateLimiter).onRateLimitResponse("personal", 4);
        }

        @Test
        void otherErrors_areEmpty() {
            steam.expect(once(), requestTo(played("api-key", "1"))).andRespond(withServerError());

            assertThat(client.getPlaytime("1", "730", null).join()).isEmpty();
        }
    }
}
