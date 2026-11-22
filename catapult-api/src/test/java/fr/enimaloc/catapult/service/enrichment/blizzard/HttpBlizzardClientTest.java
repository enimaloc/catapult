package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpBlizzardClientTest {

    private static final String EU = "https://eu.api.blizzard.com";
    private static final String CHAR = EU + "/profile/wow/character/ravencrest/inspectordob";

    private MockRestServiceServer server;
    private BlizzardAppTokenService tokens;
    private BlizzardRateLimiter limiter;
    private final AtomicInteger acquired = new AtomicInteger();
    private HttpBlizzardClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(BlizzardAppTokenService.class);
        when(tokens.token()).thenReturn("test-token");
        limiter = mock(BlizzardRateLimiter.class);
        when(limiter.budget()).thenReturn((FetchBudget) () -> {
            acquired.incrementAndGet();
            return true;
        });
        client = new HttpBlizzardClient(builder.build(), tokens, limiter, BlizzardRegion.EU);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/blizzard/" + name), StandardCharsets.UTF_8);
    }

    private static RequestMatcher bearer() {
        return header(HttpHeaders.AUTHORIZATION, "Bearer test-token");
    }

    private void json(String url, String fixture) throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(url))).andExpect(method(HttpMethod.GET))
                .andExpect(bearer()).andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_JSON));
    }

    private void wowCharacterAnswers(String mythic) throws IOException {
        json(CHAR + "?", "wow-character.json");
        json(CHAR + "/mythic-keystone-profile", mythic);
        json(CHAR + "/character-media", "wow-media.json");
    }

    @Test
    void wowCharacterReadsProfileMythicAndAvatar() throws IOException {
        wowCharacterAnswers("wow-mythic-keystone-rated.json");

        WowCharacter wow = client.wowCharacter(BlizzardRegion.EU, "ravencrest", "Inspectordob", "fr_FR");

        assertThat(wow.id()).isEqualTo(184726591L);
        assertThat(wow.name()).isEqualTo("Inspectordob");
        assertThat(wow.realmSlug()).isEqualTo("ravencrest");
        assertThat(wow.realmName()).isEqualTo("Ravencrest");
        assertThat(wow.level()).isEqualTo(80);
        assertThat(wow.className()).isEqualTo("Warrior");
        assertThat(wow.specName()).isEqualTo("Fury");
        assertThat(wow.raceName()).isEqualTo("Orc");
        assertThat(wow.faction()).isEqualTo("Horde");
        assertThat(wow.guild()).isEqualTo("Les Fous");
        assertThat(wow.achievementPoints()).isEqualTo(15230);
        assertThat(wow.averageItemLevel()).isEqualTo(620);
        assertThat(wow.equippedItemLevel()).isEqualTo(618);
        assertThat(wow.lastLogin()).isEqualTo(Instant.ofEpochMilli(1763800000000L));
        assertThat(wow.mythicRating()).isEqualTo(2431.7);
        assertThat(wow.avatarUrl()).endsWith("184726591-avatar.jpg");
        server.verify();
    }

    @Test
    void missingMythicRatingIsNull() throws IOException {
        wowCharacterAnswers("wow-mythic-keystone.json");

        WowCharacter wow = client.wowCharacter(BlizzardRegion.EU, "ravencrest", "inspectordob", "en_GB");

        assertThat(wow.mythicRating()).isNull();
    }

    @Test
    void wowNameIsLowercased() throws IOException {
        server.expect(requestTo(CHAR + "?namespace=profile-eu&locale=fr_FR")).andExpect(bearer())
                .andRespond(withSuccess(fixture("wow-character.json"), MediaType.APPLICATION_JSON));
        json(CHAR + "/mythic-keystone-profile", "wow-mythic-keystone.json");
        json(CHAR + "/character-media", "wow-media.json");

        client.wowCharacter(BlizzardRegion.EU, "ravencrest", "Inspectordob", "fr_FR");

        server.verify();
    }

    @Test
    void wowSegmentsAreEncoded() throws IOException {
        server.expect(requestTo(EU + "/profile/wow/character/ravencrest/%C3%A9l%C3%A9?namespace=profile-eu&locale=fr_FR"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body(fixture("wow-404.json"))
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.wowCharacter(BlizzardRegion.EU, "ravencrest", "Élé", "fr_FR"))
                .isInstanceOf(BlizzardNotFoundException.class);
    }

    @Test
    void wowNotFoundIsRecognisedFromTheJsonBody() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(CHAR))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .body(fixture("wow-404.json")).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.wowCharacter(BlizzardRegion.EU, "ravencrest", "inspectordob", "en_GB"))
                .isInstanceOf(BlizzardNotFoundException.class);
        assertThat(acquired).hasValue(1);
    }

    @Test
    void wowNotFoundWithAnotherBodyIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(CHAR))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .body("{\"code\":404,\"type\":\"OTHER\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.wowCharacter(BlizzardRegion.EU, "ravencrest", "inspectordob", "en_GB"))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void everyRequestAsksTheLimiter() throws IOException {
        wowCharacterAnswers("wow-mythic-keystone.json");

        client.wowCharacter(BlizzardRegion.EU, "ravencrest", "inspectordob", "en_GB");

        assertThat(acquired).hasValue(3);
    }

    @Test
    void refusedByTheLimiterMakesNoRequest() {
        when(limiter.budget()).thenReturn((FetchBudget) () -> false);

        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);
        server.verify();
    }

    @Test
    void tokenGoesInTheHeaderOnly() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/data/wow/token/index")))
                .andExpect(queryParam("namespace", "dynamic-eu")).andExpect(bearer())
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("access_token"))
                .andRespond(withSuccess(fixture("wow-token.json"), MediaType.APPLICATION_JSON));

        WowTokenPrice price = client.wowToken(BlizzardRegion.EU);

        assertThat(price.gold()).isEqualTo(470531L);
        assertThat(price.updatedAt()).isEqualTo(Instant.ofEpochMilli(1763800000000L));
        server.verify();
    }

    @Test
    void wowTokenWithoutPriceIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/data/wow/token/index")))
                .andRespond(withSuccess("{\"last_updated_timestamp\":1}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void unauthorizedRenewsTheTokenOnce() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/data/wow/token/index")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/data/wow/token/index")))
                .andRespond(withSuccess(fixture("wow-token.json"), MediaType.APPLICATION_JSON));

        assertThat(client.wowToken(BlizzardRegion.EU).gold()).isEqualTo(470531L);

        verify(tokens).invalidate("test-token");
        verify(tokens, times(2)).token();
        assertThat(acquired).hasValue(2);
    }

    @Test
    void unauthorizedTwiceIsAnError() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                        requestTo(org.hamcrest.Matchers.startsWith(EU + "/data/wow/token/index")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);
        server.verify();
    }

    @Test
    void redirectIsNeverFollowed() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/data/wow/token/index")))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION,
                        "https://evil.example/steal"));

        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);
        server.verify();
    }

    @Test
    void serverErrorsAndOtherClientErrorsAreErrors() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);
        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void status429PausesTheLimiter() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "30"));

        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);

        verify(limiter).pause(Duration.ofSeconds(30));
    }

    @Test
    void status429WithoutRetryAfterPausesAMinute() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);

        verify(limiter).pause(Duration.ofSeconds(60));
    }

    @Test
    void garbledOrWrongShapedBodiesAreErrors() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU)))
                .andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU)))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU)))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.wowToken(BlizzardRegion.EU)).isInstanceOf(BlizzardUnavailableException.class);
        }
    }

    @Test
    void achievementsProjectionKeepsTenRecent() throws IOException {
        json(CHAR + "/achievements", "wow-achievements.json");

        WowAchievements achievements = client.wowAchievements(BlizzardRegion.EU, "ravencrest", "Inspectordob", "en_GB");

        assertThat(achievements.points()).isEqualTo(15230);
        assertThat(achievements.recent()).hasSize(10);
        assertThat(achievements.recent().get(0).id()).isEqualTo("110");
        assertThat(achievements.recent().get(0).name()).isEqualTo("J");
        assertThat(achievements.recent().get(0).unlockedAt()).isEqualTo(Instant.ofEpochMilli(1763000010000L));
        assertThat(achievements.recent().get(9).id()).isEqualTo("101");
    }

    @Test
    void achievementsWithoutPointsIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(CHAR + "/achievements")))
                .andRespond(withSuccess("{\"achievements\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.wowAchievements(BlizzardRegion.EU, "ravencrest", "inspectordob", "en_GB"))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void oversizedBodyIsAnError() {
        byte[] big = new byte[8 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(CHAR + "/achievements")))
                .andRespond(withSuccess(big, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.wowAchievements(BlizzardRegion.EU, "ravencrest", "inspectordob", "en_GB"))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void userCharactersUseTheUserTokenAndNeverRenew() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/profile/user/wow")))
                .andExpect(queryParam("namespace", "profile-eu"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andRespond(withSuccess(fixture("wow-user.json"), MediaType.APPLICATION_JSON));

        List<WowUserCharacter> characters = client.wowUserCharacters(BlizzardRegion.EU, "user-token");

        assertThat(characters).hasSize(2);
        assertThat(characters.get(0).id()).isEqualTo(184726591L);
        assertThat(characters.get(0).name()).isEqualTo("Inspectordob");
        assertThat(characters.get(0).realmSlug()).isEqualTo("ravencrest");
        assertThat(characters.get(0).realmName()).isEqualTo("Ravencrest");
        assertThat(characters.get(0).className()).isEqualTo("Warrior");
        assertThat(characters.get(0).faction()).isEqualTo("Horde");
        assertThat(characters.get(0).level()).isEqualTo(80);
        verify(tokens, never()).token();
        verify(tokens, never()).invalidate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void userCharactersNotFoundIsEmptyAndForbiddenIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/profile/user/wow")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/profile/user/wow")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/profile/user/wow")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(client.wowUserCharacters(BlizzardRegion.EU, "user-token")).isEmpty();
        assertThatThrownBy(() -> client.wowUserCharacters(BlizzardRegion.EU, "user-token"))
                .isInstanceOf(BlizzardUnavailableException.class);
        assertThatThrownBy(() -> client.wowUserCharacters(BlizzardRegion.EU, "user-token"))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void userCharactersWithoutAccountsIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/profile/user/wow")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.wowUserCharacters(BlizzardRegion.EU, "user-token"))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void sc2ProfilesUseTheDefaultRegionHost() throws IOException {
        json(EU + "/sc2/player/987654", "sc2-player.json");

        List<Sc2ProfileRef> refs = client.sc2Profiles(987654L);

        assertThat(refs).containsExactly(new Sc2ProfileRef(2, 1, 1234567L, "Inspector"));
    }

    @Test
    void sc2ProfilesMustBeAnArray() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/sc2/player/1")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.sc2Profiles(1L)).isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void sc2ProfileReadsSummarySnapshotAndLadder() throws IOException {
        json(EU + "/sc2/profile/2/1/1234567", "sc2-profile.json");
        json(EU + "/sc2/profile/2/1/1234567/ladder/summary", "sc2-ladder-summary.json");

        Sc2Profile profile = client.sc2Profile(BlizzardRegion.EU, new Sc2ProfileRef(2, 1, 1234567L, "Inspector"),
                "en_GB", true);

        assertThat(profile.displayName()).isEqualTo("Inspector");
        assertThat(profile.clanName()).isEqualTo("Les Fous");
        assertThat(profile.clanTag()).isEqualTo("FOU");
        assertThat(profile.swarmLevel()).isEqualTo(58);
        assertThat(profile.achievementPoints()).isEqualTo(2480);
        assertThat(profile.modes()).containsKey("1v1");
        assertThat(profile.modes().get("1v1").leagueName()).isEqualTo("DIAMOND");
        assertThat(profile.modes().get("1v1").totalGames()).isEqualTo(40);
        assertThat(profile.modes().get("1v1").totalWins()).isEqualTo(24);
        assertThat(profile.ladder()).hasSize(2);
        assertThat(profile.ladder().get(0).mode()).isEqualTo("1v1 Diamond");
        assertThat(profile.ladder().get(0).rank()).isEqualTo(14);
        assertThat(profile.ladder().get(0).wins()).isEqualTo(24);
        assertThat(acquired).hasValue(2);
    }

    @Test
    void sc2ProfileWithoutDetailSkipsTheLadder() throws IOException {
        json(EU + "/sc2/profile/2/1/1234567", "sc2-profile.json");

        Sc2Profile profile = client.sc2Profile(BlizzardRegion.EU, new Sc2ProfileRef(2, 1, 1234567L, "Inspector"),
                "en_GB", false);

        assertThat(profile.ladder()).isEmpty();
        assertThat(acquired).hasValue(1);
    }

    @Test
    void emptyObjectIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/sc2/profile/2/1/1234567")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.sc2Profile(BlizzardRegion.EU, new Sc2ProfileRef(2, 1, 1234567L, "x"),
                "en_GB", false)).isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void d3TagKeepsCaseAndTrailingSlash() throws IOException {
        server.expect(requestTo(EU + "/d3/profile/BlackStaR-24672/?locale=en_GB")).andExpect(bearer())
                .andRespond(withSuccess(fixture("d3-profile.json"), MediaType.APPLICATION_JSON));

        D3Profile profile = client.d3Profile(BlizzardRegion.EU, "BlackStaR#24672", "en_GB");

        assertThat(profile.battleTag()).isEqualTo("BlackStaR#24672");
        assertThat(profile.paragonLevel()).isEqualTo(1050);
        assertThat(profile.paragonLevelSeason()).isEqualTo(800);
        assertThat(profile.guildName()).isEqualTo("Les Fous");
        assertThat(profile.monstersKilled()).isEqualTo(5000000L);
        assertThat(profile.elitesKilled()).isEqualTo(120000L);
        assertThat(profile.lastHeroPlayed()).isEqualTo(112233L);
        assertThat(profile.heroes()).hasSize(2);
        D3Hero hero = profile.heroes().get(0);
        assertThat(hero.name()).isEqualTo("Kane");
        assertThat(hero.className()).isEqualTo("barbarian");
        assertThat(hero.level()).isEqualTo(70);
        assertThat(hero.seasonal()).isTrue();
        assertThat(profile.heroes().get(1).dead()).isTrue();
        assertThat(profile.heroes().get(1).hardcore()).isTrue();
    }

    @Test
    void htmlNotFoundIsAnErrorNotAbsent() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/d3/profile/"))).andRespond(
                withStatus(HttpStatus.NOT_FOUND).body(fixture("d3-404.html")).contentType(MediaType.TEXT_HTML));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/d3/profile/"))).andRespond(
                withStatus(HttpStatus.NOT_FOUND).body(fixture("d3-404.json")).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.d3Profile(BlizzardRegion.EU, "a#1", "en_GB"))
                .isInstanceOf(BlizzardUnavailableException.class);
        assertThatThrownBy(() -> client.d3Profile(BlizzardRegion.EU, "a#1", "en_GB"))
                .isInstanceOf(BlizzardNotFoundException.class);
    }

    @Test
    void d3ProfileWithoutHeroesIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/d3/profile/")))
                .andRespond(withSuccess("{\"battleTag\":\"a#1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.d3Profile(BlizzardRegion.EU, "a#1", "en_GB"))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void d3CurrentSeasonReadsTheSeasonNumber() throws IOException {
        json(EU + "/data/d3/season/", "d3-seasons.json");

        assertThat(client.d3CurrentSeason(BlizzardRegion.EU)).isEqualTo(40);
    }

    @Test
    void d3CurrentSeasonMissingIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/data/d3/season/")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.d3CurrentSeason(BlizzardRegion.EU))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void hearthstoneSetsAreListedAndAnEmptyListIsNotAnError() throws IOException {
        json(EU + "/hearthstone/metadata/sets", "hs-sets.json");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/hearthstone/metadata/sets")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/hearthstone/metadata/sets")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<HearthstoneSet> sets = client.hearthstoneSets("en_US");

        assertThat(sets).hasSize(2);
        assertThat(sets.get(0).id()).isEqualTo(1900);
        assertThat(sets.get(0).name()).isEqualTo("Into the Emerald Dream");
        assertThat(sets.get(0).slug()).isEqualTo("into-the-emerald-dream");
        assertThat(sets.get(0).collectibleCount()).isEqualTo(145);
        assertThat(client.hearthstoneSets("en_US")).isEmpty();
        assertThatThrownBy(() -> client.hearthstoneSets("en_US")).isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void hearthstoneDeckEncodesTheCode() throws IOException {
        server.expect(requestTo(EU + "/hearthstone/deck?code=AAECAQcDvwHGAdYGDpAByQK%3D&locale=en_US"))
                .andExpect(bearer())
                .andRespond(withSuccess(fixture("hs-deck.json"), MediaType.APPLICATION_JSON));

        HearthstoneDeck deck = client.hearthstoneDeck("AAECAQcDvwHGAdYGDpAByQK=", "en_US");

        assertThat(deck.code()).isEqualTo("AAECAQcDvwHGAdYGDpAByQK=");
        assertThat(deck.format()).isEqualTo("standard");
        assertThat(deck.heroName()).isEqualTo("Garrosh Hellscream");
        assertThat(deck.className()).isEqualTo("Warrior");
        assertThat(deck.cardCount()).isEqualTo(30);
        assertThat(deck.cards()).hasSize(2);
        assertThat(deck.cards().get(0).name()).isEqualTo("Whirlwind");
        assertThat(deck.cards().get(0).manaCost()).isEqualTo(1);
    }

    @Test
    void hearthstoneDeckWithoutCardsIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(EU + "/hearthstone/deck")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.hearthstoneDeck("AAECAQcDvwHGAdYGDpAByQK=", "en_US"))
                .isInstanceOf(BlizzardUnavailableException.class);
    }

    @Test
    void transportNeverFollowsRedirects() {
        assertThat(HttpBlizzardClient.requestFactory()).isNotNull();
        java.net.http.HttpClient http = HttpBlizzardClient.httpClient();
        assertThat(http.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
        assertThat(http.connectTimeout()).contains(Duration.ofSeconds(5));
    }
}
