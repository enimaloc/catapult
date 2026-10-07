package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.domain.steam.SteamAppParentEntry;
import fr.enimaloc.catapult.getter.steam.SteamPlaytestRedirectResolver;
import fr.enimaloc.catapult.repository.steam.SteamAppParentRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.steam.SteamStoreService.ResolvedParentApp;
import fr.enimaloc.catapult.service.steam.SteamStoreService.SteamTwSignals;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Drives the service against Steam-shaped JSON through a real RestClient, so the tests cover
 * the {@code appdetails} deserialization too, not just the logic on top of it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SteamStoreServiceTest {

    private static final String APP_DETAILS = "https://store.steampowered.com/api/appdetails";

    @Mock private SteamAppParentRepository steamAppParentRepository;
    @Mock private SteamPlaytestRedirectResolver redirectResolver;

    private MockRestServiceServer steam;
    private SteamStoreServiceImpl service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        steam = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        ExternalApiObservations observations =
                new ExternalApiObservations(ObservationRegistry.create(), new SimpleMeterRegistry());
        service = new SteamStoreServiceImpl(builder.build(), observations, steamAppParentRepository, redirectResolver);
        doReturn(Optional.empty()).when(steamAppParentRepository).findById(any());
    }

    // --- fixtures -----------------------------------------------------------------------------

    private void givenAppDetails(String appId, String language, String json) {
        steam.expect(manyTimes(), requestTo(APP_DETAILS + "?appids=" + appId + "&l=" + language))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void givenAppDetails(String appId, String json) {
        givenAppDetails(appId, "english", json);
    }

    /**
     * Wraps {@code dataJson}'s fields in a successful entry. Real Steam responses always carry
     * steam_appid, required_age and is_free; Jackson 3 refuses to map a missing primitive, so
     * leaving them out would make every fixture fail to deserialize.
     */
    private static String success(String appId, String dataJson) {
        String body = dataJson.strip();
        String fields = body.substring(1, body.length() - 1).strip();
        String always = "\"steam_appid\": %s, \"required_age\": 0, \"is_free\": false".formatted(appId);
        return "{\"%s\": {\"success\": true, \"data\": {%s%s}}}"
                .formatted(appId, always, fields.isEmpty() ? "" : ", " + fields);
    }

    private static String failure(String appId) {
        return "{\"%s\": {\"success\": false}}".formatted(appId);
    }

    private static String rated(String appId, String descriptors) {
        return success(appId, """
                {"type": "game", "name": "Some Game",
                 "ratings": {"esrb": {"rating": "t", "descriptors": "%s"}}}""".formatted(descriptors));
    }

    // --- fetchCcls ----------------------------------------------------------------------------

    @Test
    void fetchCcls_emptyAppIds_returnsImmediately() {
        assertThat(service.fetchCcls(List.of())).isEmpty();
    }

    @Test
    void fetchCcls_ratingWithoutDescriptors_returnsNoEntry() {
        // MatureGame is handled automatically by Twitch, not suggested by this service
        givenAppDetails("100", rated("100", ""));

        assertThat(service.fetchCcls(List.of("100"))).doesNotContainKey("100");
    }

    @Test
    void fetchCcls_bloodDescriptor_addsViolentGraphic() {
        givenAppDetails("100", rated("100", "Blood and Gore"));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("ViolentGraphic");
    }

    @Test
    void fetchCcls_nudityDescriptor_addsSexualThemes() {
        givenAppDetails("100", rated("100", "Nudity"));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("SexualThemes");
    }

    @Test
    void fetchCcls_drugDescriptor_addsDrugsIntoxication() {
        givenAppDetails("100", rated("100", "Drug Reference"));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("DrugsIntoxication");
    }

    @Test
    void fetchCcls_gamblingDescriptor_addsGambling() {
        givenAppDetails("100", rated("100", "Simulated Gambling"));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("Gambling");
    }

    @Test
    void fetchCcls_profanityDescriptor_addsProfanityVulgarity() {
        givenAppDetails("100", rated("100", "Strong Language"));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("ProfanityVulgarity");
    }

    @Test
    void fetchCcls_multipleDescriptors_addsMultipleCcls() {
        givenAppDetails("100", rated("100", "Blood and Gore\\nDrug Reference"));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("ViolentGraphic", "DrugsIntoxication");
    }

    @Test
    void fetchCcls_descriptorsMatchedAcrossEveryRatingBoard() {
        givenAppDetails("100", success("100", """
                {"ratings": {"esrb": {"descriptors": "Blood"}, "pegi": {"descriptors": "Gambling"}}}"""));

        assertThat(service.fetchCcls(List.of("100")).get("100")).containsExactlyInAnyOrder("ViolentGraphic", "Gambling");
    }

    @Test
    void fetchCcls_ratingMissingDescriptorsField_isSkipped() {
        givenAppDetails("100", success("100", """
                {"ratings": {"esrb": {"rating": "m"}, "pegi": {"descriptors": "Violence"}}}"""));

        assertThat(service.fetchCcls(List.of("100")).get("100")).containsExactly("ViolentGraphic");
    }

    @Test
    void fetchCcls_noRatings_returnsEmpty() {
        givenAppDetails("100", success("100", "{}"));

        assertThat(service.fetchCcls(List.of("100"))).isEmpty();
    }

    @Test
    void fetchCcls_successFalse_skipsEntry() {
        givenAppDetails("100", failure("100"));

        assertThat(service.fetchCcls(List.of("100"))).isEmpty();
    }

    @Test
    void fetchCcls_oneUnresolvedApp_doesNotDropTheOthers() {
        givenAppDetails("100", failure("100"));
        givenAppDetails("200", rated("200", "Blood"));

        assertThat(service.fetchCcls(List.of("100", "200")))
                .containsOnlyKeys("200")
                .containsEntry("200", Set.of("ViolentGraphic"));
    }

    @Test
    void fetchCcls_steamError_returnsEmpty() {
        steam.expect(manyTimes(), requestTo(APP_DETAILS + "?appids=100&l=english"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(service.fetchCcls(List.of("100"))).isEmpty();
    }

    // --- fetchTwSignals -----------------------------------------------------------------------

    @Test
    void fetchTwSignals_emptyAppIds_returnsImmediately() {
        assertThat(service.fetchTwSignals(List.of())).isEmpty();
    }

    @Test
    void fetchTwSignals_readsDescriptorIdsAndLowercasedNotes() {
        givenAppDetails("100", success("100", """
                {"content_descriptors": {"ids": [2, 5], "notes": "Contains Blood and GORE"}}"""));

        assertThat(service.fetchTwSignals(List.of("100")))
                .containsEntry("100", new SteamTwSignals(Set.of(2, 5), "contains blood and gore"));
    }

    @Test
    void fetchTwSignals_noContentDescriptors_returnsEmptySignals() {
        givenAppDetails("100", success("100", "{}"));

        assertThat(service.fetchTwSignals(List.of("100"))).containsEntry("100", SteamTwSignals.empty());
    }

    @Test
    void fetchTwSignals_unresolvedApp_isSkipped() {
        givenAppDetails("100", failure("100"));
        givenAppDetails("200", success("200", "{\"content_descriptors\": {\"ids\": [1]}}"));

        assertThat(service.fetchTwSignals(List.of("100", "200"))).containsOnlyKeys("200");
    }

    // --- resolveEffectiveApp ------------------------------------------------------------------

    @Test
    void resolveEffectiveApp_freshCacheHit_skipsNetworkCall() {
        doReturn(Optional.of(new SteamAppParentEntry("4519120", "4009490", "Arctic Drive")))
                .when(steamAppParentRepository).findById("4519120");

        assertThat(service.resolveEffectiveApp("4519120")).contains(new ResolvedParentApp("4009490", "Arctic Drive"));
        steam.verify();
        verifyNoInteractions(redirectResolver);
    }

    @Test
    void resolveEffectiveApp_freshNegativeCacheHit_skipsNetworkCall() {
        doReturn(Optional.of(new SteamAppParentEntry("730", null, null)))
                .when(steamAppParentRepository).findById("730");

        assertThat(service.resolveEffectiveApp("730")).isEmpty();
        steam.verify();
        verifyNoInteractions(redirectResolver);
    }

    @Test
    void resolveEffectiveApp_fullgamePresent_returnsParentFromSameCall() {
        givenAppDetails("100", success("100", """
                {"type": "demo", "name": "Some Demo", "fullgame": {"appid": "200", "name": "Some Game"}}"""));

        assertThat(service.resolveEffectiveApp("100")).contains(new ResolvedParentApp("200", "Some Game"));
        verifyNoInteractions(redirectResolver);
        verify(steamAppParentRepository).save(argThat(e ->
                "100".equals(e.getAppId()) && "200".equals(e.getParentAppId()) && "Some Game".equals(e.getParentName())));
    }

    @Test
    void resolveEffectiveApp_fullgameWithoutName_usesItsAppIdAsName() {
        givenAppDetails("100", success("100", "{\"type\": \"demo\", \"fullgame\": {\"appid\": 200}}"));

        assertThat(service.resolveEffectiveApp("100")).contains(new ResolvedParentApp("200", "200"));
    }

    @Test
    void resolveEffectiveApp_playtestName_redirectResolved_returnsParent() {
        givenAppDetails("4519120", success("4519120", "{\"type\": \"game\", \"name\": \"Arctic Drive Playtest\"}"));
        givenAppDetails("4009490", success("4009490", "{\"type\": \"game\", \"name\": \"Arctic Drive\"}"));
        doReturn(Optional.of("4009490")).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120")).contains(new ResolvedParentApp("4009490", "Arctic Drive"));
        verify(steamAppParentRepository).save(argThat(e ->
                "4519120".equals(e.getAppId()) && "4009490".equals(e.getParentAppId())
                        && "Arctic Drive".equals(e.getParentName())));
    }

    @Test
    void resolveEffectiveApp_playtestName_parentDetailsFetchFails_returnsIdFallbackWithoutCaching() {
        givenAppDetails("4519120", success("4519120", "{\"type\": \"game\", \"name\": \"Arctic Drive Playtest\"}"));
        givenAppDetails("4009490", failure("4009490"));
        doReturn(Optional.of("4009490")).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120")).contains(new ResolvedParentApp("4009490", "4009490"));
        verify(steamAppParentRepository, never()).save(any());
    }

    @Test
    void resolveEffectiveApp_playtestName_redirectFails_returnsEmptyWithoutCaching() {
        givenAppDetails("4519120", success("4519120", "{\"type\": \"game\", \"name\": \"Arctic Drive Playtest\"}"));
        doReturn(Optional.empty()).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120")).isEmpty();
        verify(steamAppParentRepository, never()).save(any());
    }

    @Test
    void resolveEffectiveApp_regularGame_cachesNoParent() {
        givenAppDetails("730", success("730", "{\"type\": \"game\", \"name\": \"Counter-Strike 2\"}"));

        assertThat(service.resolveEffectiveApp("730")).isEmpty();
        verifyNoInteractions(redirectResolver);
        verify(steamAppParentRepository).save(argThat(e -> "730".equals(e.getAppId()) && e.getParentAppId() == null));
    }

    @Test
    void resolveEffectiveApp_unknownApp_returnsEmptyWithoutCaching() {
        givenAppDetails("100", failure("100"));

        assertThat(service.resolveEffectiveApp("100")).isEmpty();
        verify(steamAppParentRepository, never()).save(any());
    }

    @Test
    void resolveEffectiveApp_cacheLookupThrows_fallsBackToLiveResolution() {
        doThrow(new RuntimeException("db down")).when(steamAppParentRepository).findById("100");
        givenAppDetails("100", success("100", """
                {"type": "demo", "name": "Some Demo", "fullgame": {"appid": "200", "name": "Some Game"}}"""));

        assertThat(service.resolveEffectiveApp("100")).contains(new ResolvedParentApp("200", "Some Game"));
    }

    @Test
    void resolveEffectiveApp_cacheSaveThrows_stillReturnsResolution() {
        doThrow(new RuntimeException("db down")).when(steamAppParentRepository).save(any());
        givenAppDetails("100", success("100", """
                {"type": "demo", "fullgame": {"appid": "200", "name": "Some Game"}}"""));

        assertThat(service.resolveEffectiveApp("100")).contains(new ResolvedParentApp("200", "Some Game"));
    }

    @Test
    void resolveEffectiveApp_staleCache_reResolves() {
        SteamAppParentEntry stale = new SteamAppParentEntry("4519120", "4009490", "Arctic Drive");
        stale.setResolvedAt(Instant.now().minus(31, ChronoUnit.DAYS));
        doReturn(Optional.of(stale)).when(steamAppParentRepository).findById("4519120");
        givenAppDetails("4519120", success("4519120", "{\"type\": \"game\", \"name\": \"Arctic Drive Playtest\"}"));
        doReturn(Optional.empty()).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120")).isEmpty();
        verify(redirectResolver).resolveParentAppId("4519120");
    }

    // --- fetchDescription / fetchData ---------------------------------------------------------

    @Test
    void fetchDescription_present_returnsShortDescriptionInTheRequestedLanguage() {
        givenAppDetails("100", success("100", "{\"type\": \"game\", \"name\": \"Some Game\"}"));
        givenAppDetails("100", "french", success("100", """
                {"type": "game", "name": "Some Game", "short_description": "Une description en français."}"""));

        assertThat(service.fetchDescription("100", Locale.FRENCH)).contains("Une description en français.");
    }

    @Test
    void fetchDescription_blank_returnsEmpty() {
        givenAppDetails("100", success("100", "{\"type\": \"game\", \"name\": \"Some Game\", \"short_description\": \"\"}"));

        assertThat(service.fetchDescription("100", Locale.ENGLISH)).isEmpty();
    }

    @Test
    void fetchDescription_unknownApp_returnsEmpty() {
        givenAppDetails("100", failure("100"));

        assertThat(service.fetchDescription("100", Locale.ENGLISH)).isEmpty();
    }

    @Test
    void fetchDescription_demo_readsTheFullGamesDescription() {
        givenAppDetails("100", success("100", """
                {"type": "demo", "fullgame": {"appid": "200", "name": "Some Game"}}"""));
        givenAppDetails("200", success("200", "{\"type\": \"game\", \"short_description\": \"Full game.\"}"));

        assertThat(service.fetchDescription("100", Locale.ENGLISH)).contains("Full game.");
    }

    @Test
    void fetchData_appMissingFromResponse_returnsEmpty() {
        givenAppDetails("100", "{}");

        assertThat(service.fetchData("100", Locale.ENGLISH, false)).isEmpty();
    }

    @Test
    void fetchData_mapsStorePageFields() {
        givenAppDetails("100", success("100", """
                {"type": "game", "name": "Some Game",
                 "developers": ["Studio"], "release_date": {"coming_soon": false, "date": "1 Jan, 2024"}}"""));

        SteamStoreService.SteamStorePage page = service.fetchData("100", Locale.ENGLISH, false).orElseThrow();
        assertThat(page.name()).isEqualTo("Some Game");
        assertThat(page.steamAppId()).isEqualTo(100);
        assertThat(page.isFree()).isFalse();
        assertThat(page.developers()).containsExactly("Studio");
        assertThat(page.releaseDate().date()).isEqualTo("1 Jan, 2024");
    }

    // --- fetchIADisclosure --------------------------------------------------------------------

    @Test
    void fetchIADisclosure_returnsTheStorePageHtml() {
        givenAppDetails("100", success("100", "{\"type\": \"game\"}"));
        steam.expect(manyTimes(), requestTo("https://store.steampowered.com/app/100?l=french"))
                .andRespond(withSuccess("<html>page</html>", MediaType.TEXT_HTML));

        assertThat(service.fetchIADisclosure("100", Locale.FRENCH)).contains("<html>page</html>");
    }

    @Test
    void fetchIADisclosure_storeError_returnsEmpty() {
        givenAppDetails("100", success("100", "{\"type\": \"game\"}"));
        steam.expect(manyTimes(), requestTo("https://store.steampowered.com/app/100?l=english"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(service.fetchIADisclosure("100", Locale.ENGLISH)).isEmpty();
    }
}
