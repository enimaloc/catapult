package fr.enimaloc.catapult.chat.command.js;

import com.google.protobuf.Timestamp;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.IgdbClient;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.SteamStoreService;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;
import proto.Genre;
import proto.Platform;
import proto.Website;
import proto.WebsiteCategoryEnum;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultChatCommandServiceGatewayTest {

    @Mock private IgdbClient igdbClient;
    @Mock private IgdbService igdbService;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private SteamStoreService steamStoreService;

    private DefaultChatCommandServiceGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new DefaultChatCommandServiceGateway(igdbClient, igdbService, restClient,
            new ExternalApiObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry()), steamStoreService);
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString(), any(Object[].class));
        doReturn(responseSpec).when(headersSpec).retrieve();
        when(igdbService.getAppToken()).thenReturn("app-token");
    }

    @Test
    void igdbGameReturnsTheEnrichedGameObject() {
        Game searchResult = Game.newBuilder().setId(1234L).setName("VALORANT").build();
        when(igdbClient.searchByName("Valorant", "app-token")).thenReturn(List.of(searchResult));

        Platform pc = Platform.newBuilder().setName("PC").build();
        Platform ps5 = Platform.newBuilder().setName("PlayStation 5").build();
        Game details = Game.newBuilder()
            .setId(1234L)
            .setName("VALORANT")
            .setSlug("valorant")
            .setSummary("A tactical shooter.")
            .setFirstReleaseDate(Timestamp.newBuilder().setSeconds(1591056000L).build())
            .setRating(85.4)
            .setAggregatedRating(79.6)
            .addPlatforms(pc)
            .addPlatforms(ps5)
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        var result = gateway.igdbGame("Valorant");

        assertThat(result).isPresent();
        var game = result.get();
        assertThat(game.id()).isEqualTo("1234");
        assertThat(game.name()).isEqualTo("VALORANT");
        assertThat(game.summary()).isEqualTo("A tactical shooter.");
        assertThat(game.steamReleaseDate()).isEqualTo("2020-06-02");
        assertThat(game.rating()).isEqualTo("85");
        assertThat(game.criticRating()).isEqualTo("80");
        assertThat(game.platforms()).isEqualTo("PC, PlayStation 5");
        assertThat(game.igdbUrl()).isEqualTo("https://www.igdb.com/games/valorant");
    }

    @Test
    void igdbGameFallsBackToTheBareSearchResultWhenDetailsFetchReturnsEmpty() {
        Game searchResult = Game.newBuilder().setId(1234L).setName("VALORANT").build();
        when(igdbClient.searchByName("Valorant", "app-token")).thenReturn(List.of(searchResult));
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.empty());

        var result = gateway.igdbGame("Valorant");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("1234");
        assertThat(result.get().name()).isEqualTo("VALORANT");
        assertThat(result.get().summary()).isEmpty();
        assertThat(result.get().steamReleaseDate()).isEmpty();
    }

    @Test
    void igdbExternalPlatformsMergesWebsitesAndExternalGames() {
        Website official = Website.newBuilder()
            .setCategory(WebsiteCategoryEnum.WEBSITE_OFFICIAL)
            .setUrl("https://playvalorant.com")
            .build();
        ExternalGameSource steamSource = ExternalGameSource.newBuilder().setName("Steam").build();
        ExternalGame steamLink = ExternalGame.newBuilder()
            .setUid("1234560")
            .setExternalGameSource(steamSource)
            .build();
        Game details = Game.newBuilder()
            .setId(1234L)
            .addWebsites(official)
            .addExternalGames(steamLink)
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        var result = gateway.igdbExternalPlatforms("1234");

        assertThat(result).isPresent();
        assertThat(result.get())
            .containsEntry("official", "https://playvalorant.com")
            .containsEntry("steam", "1234560");
    }

    @Test
    void igdbExternalPlatformsReturnsEmptyWhenDetailsFetchReturnsEmpty() {
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.empty());

        assertThat(gateway.igdbExternalPlatforms("1234")).isEmpty();
    }

    @Test
    void igdbExternalPlatformsReturnsEmptyWhenAppTokenIsBlank() {
        when(igdbService.getAppToken()).thenReturn("");

        assertThat(gateway.igdbExternalPlatforms("1234")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(igdbClient);
    }

    @Test
    void igdbGenresReturnsGenreNameList() {
        Genre shooter = Genre.newBuilder().setName("Shooter").build();
        Genre tactical = Genre.newBuilder().setName("Tactical").build();
        Game details = Game.newBuilder().setId(1234L).addGenres(shooter).addGenres(tactical).build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbGenres("1234")).contains(List.of("Shooter", "Tactical"));
    }

    @Test
    void igdbGenresReturnsEmptyWhenDetailsFetchReturnsEmpty() {
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.empty());

        assertThat(gateway.igdbGenres("1234")).isEmpty();
    }

    @Test
    void igdbCoverReturnsTheCoverObject() {
        Game details = Game.newBuilder().setId(1234L)
            .setCover(proto.Cover.newBuilder().setUrl("//images.igdb.com/cover.jpg").setWidth(264).setHeight(352).build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbCover("1234")).contains(
            Map.of("url", "//images.igdb.com/cover.jpg", "width", 264, "height", 352));
    }

    @Test
    void igdbCoverReturnsEmptyMapWhenGameHasNoCover() {
        Game details = Game.newBuilder().setId(1234L).build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbCover("1234")).contains(Map.of());
    }

    @Test
    void igdbScreenshotsReturnsUrlList() {
        Game details = Game.newBuilder().setId(1234L)
            .addScreenshots(proto.Screenshot.newBuilder().setUrl("//images.igdb.com/s1.jpg").build())
            .addScreenshots(proto.Screenshot.newBuilder().setUrl("//images.igdb.com/s2.jpg").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbScreenshots("1234"))
            .contains(List.of("//images.igdb.com/s1.jpg", "//images.igdb.com/s2.jpg"));
    }

    @Test
    void igdbVideosReturnsNameAndYoutubeUrlObjects() {
        Game details = Game.newBuilder().setId(1234L)
            .addVideos(proto.GameVideo.newBuilder().setName("Trailer").setVideoId("abc123").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbVideos("1234")).contains(
            List.of(Map.of("name", "Trailer", "url", "https://www.youtube.com/watch?v=abc123")));
    }

    @Test
    void igdbGameModesReturnsNameList() {
        Game details = Game.newBuilder().setId(1234L)
            .addGameModes(proto.GameMode.newBuilder().setName("Single player").build())
            .addGameModes(proto.GameMode.newBuilder().setName("Co-operative").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbGameModes("1234")).contains(List.of("Single player", "Co-operative"));
    }

    @Test
    void igdbThemesReturnsNameList() {
        Game details = Game.newBuilder().setId(1234L)
            .addThemes(proto.Theme.newBuilder().setName("Action").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbThemes("1234")).contains(List.of("Action"));
    }

    @Test
    void igdbPlayerPerspectivesReturnsNameList() {
        Game details = Game.newBuilder().setId(1234L)
            .addPlayerPerspectives(proto.PlayerPerspective.newBuilder().setName("First person").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbPlayerPerspectives("1234")).contains(List.of("First person"));
    }

    @Test
    void igdbInvolvedCompaniesReturnsObjectsWithRoleFlags() {
        proto.Company company = proto.Company.newBuilder().setName("Riot Games").build();
        proto.InvolvedCompany involved = proto.InvolvedCompany.newBuilder()
            .setCompany(company)
            .setDeveloper(true)
            .setPublisher(true)
            .build();
        Game details = Game.newBuilder().setId(1234L).addInvolvedCompanies(involved).build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        var result = gateway.igdbInvolvedCompanies("1234");
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0))
            .containsEntry("name", "Riot Games")
            .containsEntry("developer", true)
            .containsEntry("publisher", true)
            .containsEntry("supporting", false)
            .containsEntry("porting", false);
    }

    @Test
    void igdbAgeRatingsReturnsOrganizationAndRatingObjects() {
        proto.AgeRatingOrganization esrb = proto.AgeRatingOrganization.newBuilder().setName("ESRB").build();
        proto.AgeRating ageRating = proto.AgeRating.newBuilder()
            .setOrganization(esrb)
            .setRating(proto.AgeRatingRatingEnum.M)
            .build();
        Game details = Game.newBuilder().setId(1234L).addAgeRatings(ageRating).build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbAgeRatings("1234")).contains(
            List.of(Map.of("organization", "ESRB", "rating", "M")));
    }

    @Test
    void igdbFranchisesReturnsNameList() {
        Game details = Game.newBuilder().setId(1234L)
            .addFranchises(proto.Franchise.newBuilder().setName("Half-Life").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbFranchises("1234")).contains(List.of("Half-Life"));
    }

    @Test
    void igdbKeywordsReturnsNameList() {
        Game details = Game.newBuilder().setId(1234L)
            .addKeywords(proto.Keyword.newBuilder().setName("post-apocalyptic").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbKeywords("1234")).contains(List.of("post-apocalyptic"));
    }

    @Test
    void igdbSimilarGamesReturnsNameList() {
        Game details = Game.newBuilder().setId(1234L)
            .addSimilarGames(Game.newBuilder().setName("Counter-Strike 2").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbSimilarGames("1234")).contains(List.of("Counter-Strike 2"));
    }

    @Test
    void igdbDlcsReturnsNameList() {
        Game details = Game.newBuilder().setId(1234L)
            .addDlcs(Game.newBuilder().setName("Some DLC").build())
            .build();
        when(igdbClient.fetchGameDetails("1234", "app-token")).thenReturn(Optional.of(details));

        assertThat(gateway.igdbDlcs("1234")).contains(List.of("Some DLC"));
    }

    @Test
    void igdbGameReturnsEmptyWhenNoSearchResults() {
        when(igdbClient.searchByName("Unknown", "app-token")).thenReturn(List.of());

        assertThat(gateway.igdbGame("Unknown")).isEmpty();
    }

    @Test
    void igdbGameReturnsEmptyWhenClientThrows() {
        when(igdbClient.searchByName("Boom", "app-token")).thenThrow(new RuntimeException("igdb down"));

        assertThat(gateway.igdbGame("Boom")).isEmpty();
    }

    @Test
    void igdbGameReturnsEmptyWhenAppTokenIsBlank() {
        when(igdbService.getAppToken()).thenReturn("");

        assertThat(gateway.igdbGame("Valorant")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(igdbClient);
    }

    @Test
    void twitchOwnDisplayNameReturnsTwitchUsername() {
        UserAccount user = new UserAccount();
        user.setTwitchUsername("SomeStreamer");

        assertThat(gateway.twitchOwnDisplayName(user)).contains("SomeStreamer");
    }

    @Test
    void twitchOwnDisplayNameReturnsEmptyForNullUser() {
        assertThat(gateway.twitchOwnDisplayName(null)).isEmpty();
    }

    @Test
    void steamPriceReturnsFormattedPrice() {
        Map<String, Object> body = Map.of("1091500", Map.of(
            "success", true,
            "data", Map.of("price_overview", Map.of("final_formatted", "59,99€"))
        ));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(gateway.steamPrice("1091500")).contains("59,99€");
    }

    @Test
    void steamPriceReturnsEmptyWhenSuccessFalse() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", false));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(gateway.steamPrice("1091500")).isEmpty();
    }

    @Test
    void steamPriceReturnsEmptyWhenRestClientThrows() {
        doReturn(getSpec).when(restClient).get();
        when(getSpec.uri(anyString(), any(Object[].class))).thenThrow(new RuntimeException("network down"));

        assertThat(gateway.steamPrice("1091500")).isEmpty();
    }

    @Test
    void steamGameReturnsTheDataObjectWhenFound() {
        Map<String, Object> body = Map.of("1091500", Map.of(
            "success", true,
            "data", Map.of("name", "Cyberpunk 2077", "type", "game")
        ));
        doReturn(body).when(responseSpec).body(Map.class);

        Optional<Object> result = gateway.steamGame("1091500", null);

        assertThat(result).isPresent();
        assertThat(((Map<?, ?>) result.get()).get("name")).isEqualTo("Cyberpunk 2077");
    }

    @Test
    void steamGameDefaultsToEnglishWhenNoLocaleGiven() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", true, "data", Map.of("name", "Game")));
        doReturn(body).when(responseSpec).body(Map.class);

        gateway.steamGame("1091500", null);

        org.mockito.Mockito.verify(getSpec).uri(anyString(), org.mockito.ArgumentMatchers.eq("1091500"), org.mockito.ArgumentMatchers.eq("english"));
    }

    @Test
    void steamGamePassesThroughAnExplicitLocale() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", true, "data", Map.of("name", "Jeu")));
        doReturn(body).when(responseSpec).body(Map.class);

        gateway.steamGame("1091500", "french");

        org.mockito.Mockito.verify(getSpec).uri(anyString(), org.mockito.ArgumentMatchers.eq("1091500"), org.mockito.ArgumentMatchers.eq("french"));
    }

    @Test
    void steamGameReturnsEmptyWhenSuccessFalse() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", false));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(gateway.steamGame("1091500", null)).isEmpty();
    }

    @Test
    void steamGameReturnsEmptyWhenRestClientThrows() {
        doReturn(getSpec).when(restClient).get();
        when(getSpec.uri(anyString(), any(Object[].class))).thenThrow(new RuntimeException("network down"));

        assertThat(gateway.steamGame("1091500", null)).isEmpty();
    }

    @Test
    void steamGameResolvesToParent_whenAppIdIsAPlaytest() {
        when(steamStoreService.resolveEffectiveApp("4519120"))
            .thenReturn(Optional.of(new SteamStoreService.ResolvedParentApp("4009490", "Arctic Drive")));
        Map<String, Object> body = Map.of("4009490", Map.of(
            "success", true, "data", Map.of("name", "Arctic Drive", "type", "game")));
        doReturn(body).when(responseSpec).body(Map.class);

        Optional<Object> result = gateway.steamGame("4519120", null);

        assertThat(result).isPresent();
        assertThat(((Map<?, ?>) result.get()).get("name")).isEqualTo("Arctic Drive");
        org.mockito.Mockito.verify(getSpec).uri(anyString(), org.mockito.ArgumentMatchers.eq("4009490"), org.mockito.ArgumentMatchers.eq("english"));
    }
}
