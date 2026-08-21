package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.SteamAppParentEntry;
import fr.enimaloc.catapult.getter.SteamPlaytestRedirectResolver;
import fr.enimaloc.catapult.repository.SteamAppParentRepository;
import fr.enimaloc.catapult.service.SteamStoreServiceImpl;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SteamStoreServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private SteamAppParentRepository steamAppParentRepository;
    @Mock private SteamPlaytestRedirectResolver redirectResolver;

    @Spy
    private ExternalApiObservations apiObservations = new ExternalApiObservations(ObservationRegistry.create(), new SimpleMeterRegistry());

    @InjectMocks private SteamStoreServiceImpl service;

    @BeforeEach
    void setupRestClientChain() {
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    private void givenSteamResponse(Map<String, Object> response) {
        doReturn(response).when(responseSpec).body(Map.class);
    }

    private Map<String, Object> steamEntry(String org, String rating, String descriptors) {
        return Map.of("success", true, "data", Map.of(
            "ratings", Map.of(org, Map.of("rating", rating, "descriptors", descriptors))
        ));
    }

    @Test
    void fetchCcls_esrbM_noDescriptors_returnsNoEntry() {
        givenSteamResponse(Map.of("100", steamEntry("esrb", "m", "")));

        // MatureGame is handled automatically by Twitch, not suggested by this service
        assertThat(service.fetchCcls(List.of("100"))).doesNotContainKey("100");
    }

    @Test
    void fetchCcls_bloodDescriptor_addsViolentGraphic() {
        givenSteamResponse(Map.of("100", steamEntry("esrb", "t", "Blood and Gore")));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("ViolentGraphic");
    }

    @Test
    void fetchCcls_nudityDescriptor_addsSexualThemes() {
        givenSteamResponse(Map.of("100", steamEntry("esrb", "t", "Nudity")));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("SexualThemes");
    }

    @Test
    void fetchCcls_drugDescriptor_addsDrugsIntoxication() {
        givenSteamResponse(Map.of("100", steamEntry("esrb", "t", "Drug Reference")));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("DrugsIntoxication");
    }

    @Test
    void fetchCcls_gamblingDescriptor_addsGambling() {
        givenSteamResponse(Map.of("100", steamEntry("esrb", "t", "Simulated Gambling")));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("Gambling");
    }

    @Test
    void fetchCcls_profanityDescriptor_addsProfanityVulgarity() {
        givenSteamResponse(Map.of("100", steamEntry("esrb", "t", "Strong Language")));

        assertThat(service.fetchCcls(List.of("100")).get("100")).contains("ProfanityVulgarity");
    }

    @Test
    void fetchCcls_multipleDescriptors_addsMultipleCcls() {
        givenSteamResponse(Map.of("100", steamEntry("esrb", "m", "Blood and Gore\nDrug Reference")));

        Set<String> ccls = service.fetchCcls(List.of("100")).get("100");
        assertThat(ccls).contains("ViolentGraphic", "DrugsIntoxication");
    }

    @Test
    void fetchCcls_noRatings_returnsEmpty() {
        givenSteamResponse(Map.of("100", Map.of("success", true, "data", Map.of())));

        assertThat(service.fetchCcls(List.of("100"))).isEmpty();
    }

    @Test
    void fetchCcls_successFalse_skipsEntry() {
        givenSteamResponse(Map.of("100", Map.of("success", false)));

        assertThat(service.fetchCcls(List.of("100"))).isEmpty();
    }

    @Test
    void fetchCcls_nullResponse_returnsEmpty() {
        doReturn(null).when(responseSpec).body(Map.class);

        assertThat(service.fetchCcls(List.of("100"))).isEmpty();
    }

    @Test
    void fetchCcls_emptyAppIds_returnsImmediately() {
        assertThat(service.fetchCcls(List.of())).isEmpty();
    }

    @Test
    void resolveEffectiveApp_freshCacheHit_skipsNetworkCall() {
        SteamAppParentEntry cached = new SteamAppParentEntry("4519120", "4009490", "Arctic Drive");
        doReturn(java.util.Optional.of(cached)).when(steamAppParentRepository).findById("4519120");

        assertThat(service.resolveEffectiveApp("4519120"))
            .contains(new SteamStoreService.ResolvedParentApp("4009490", "Arctic Drive"));
        verifyNoInteractions(restClient);
        verifyNoInteractions(redirectResolver);
    }

    @Test
    void resolveEffectiveApp_freshNegativeCacheHit_skipsNetworkCall() {
        SteamAppParentEntry cached = new SteamAppParentEntry("730", null, null);
        doReturn(java.util.Optional.of(cached)).when(steamAppParentRepository).findById("730");

        assertThat(service.resolveEffectiveApp("730")).isEmpty();
        verifyNoInteractions(restClient);
        verifyNoInteractions(redirectResolver);
    }

    @Test
    void resolveEffectiveApp_fullgamePresent_returnsParentFromSameCall() {
        doReturn(java.util.Optional.empty()).when(steamAppParentRepository).findById("100");
        Map<String, Object> body = Map.of("100", Map.of(
            "success", true,
            "data", Map.of("type", "demo", "name", "Some Demo",
                "fullgame", Map.of("appid", "200", "name", "Some Game"))
        ));
        givenSteamResponse(body);

        assertThat(service.resolveEffectiveApp("100"))
            .contains(new SteamStoreService.ResolvedParentApp("200", "Some Game"));
        verifyNoInteractions(redirectResolver);
        verify(steamAppParentRepository).save(argThat(e ->
            "100".equals(e.getAppId()) && "200".equals(e.getParentAppId()) && "Some Game".equals(e.getParentName())));
    }

    @Test
    void resolveEffectiveApp_playtestName_redirectResolved_returnsParent() {
        doReturn(java.util.Optional.empty()).when(steamAppParentRepository).findById("4519120");
        Map<String, Object> playtestBody = Map.of("4519120", Map.of(
            "success", true, "data", Map.of("type", "game", "name", "Arctic Drive Playtest")));
        Map<String, Object> parentBody = Map.of("4009490", Map.of(
            "success", true, "data", Map.of("type", "game", "name", "Arctic Drive")));
        doReturn(playtestBody).doReturn(parentBody).when(responseSpec).body(Map.class);
        doReturn(java.util.Optional.of("4009490")).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120"))
            .contains(new SteamStoreService.ResolvedParentApp("4009490", "Arctic Drive"));
        verify(steamAppParentRepository).save(argThat(e ->
            "4519120".equals(e.getAppId()) && "4009490".equals(e.getParentAppId()) && "Arctic Drive".equals(e.getParentName())));
    }

    @Test
    void resolveEffectiveApp_playtestName_parentDetailsFetchFails_returnsIdFallbackWithoutCaching() {
        doReturn(java.util.Optional.empty()).when(steamAppParentRepository).findById("4519120");
        Map<String, Object> playtestBody = Map.of("4519120", Map.of(
            "success", true, "data", Map.of("type", "game", "name", "Arctic Drive Playtest")));
        Map<String, Object> failedParentBody = Map.of("4009490", Map.of("success", false));
        doReturn(playtestBody).doReturn(failedParentBody).when(responseSpec).body(Map.class);
        doReturn(java.util.Optional.of("4009490")).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120"))
            .contains(new SteamStoreService.ResolvedParentApp("4009490", "4009490"));
        verify(steamAppParentRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveEffectiveApp_playtestName_redirectFails_returnsEmptyWithoutCaching() {
        doReturn(java.util.Optional.empty()).when(steamAppParentRepository).findById("4519120");
        Map<String, Object> playtestBody = Map.of("4519120", Map.of(
            "success", true, "data", Map.of("type", "game", "name", "Arctic Drive Playtest")));
        givenSteamResponse(playtestBody);
        doReturn(java.util.Optional.empty()).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120")).isEmpty();
        verify(steamAppParentRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveEffectiveApp_regularGame_cachesNoParent() {
        doReturn(java.util.Optional.empty()).when(steamAppParentRepository).findById("730");
        Map<String, Object> body = Map.of("730", Map.of(
            "success", true, "data", Map.of("type", "game", "name", "Counter-Strike 2")));
        givenSteamResponse(body);

        assertThat(service.resolveEffectiveApp("730")).isEmpty();
        verifyNoInteractions(redirectResolver);
        verify(steamAppParentRepository).save(argThat(e ->
            "730".equals(e.getAppId()) && e.getParentAppId() == null));
    }

    @Test
    void resolveEffectiveApp_cacheLookupThrows_fallsBackToLiveResolution() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(steamAppParentRepository).findById("100");
        Map<String, Object> body = Map.of("100", Map.of(
            "success", true,
            "data", Map.of("type", "demo", "name", "Some Demo",
                "fullgame", Map.of("appid", "200", "name", "Some Game"))
        ));
        givenSteamResponse(body);

        assertThat(service.resolveEffectiveApp("100"))
            .contains(new SteamStoreService.ResolvedParentApp("200", "Some Game"));
    }

    @Test
    void resolveEffectiveApp_staleCache_reResolves() {
        SteamAppParentEntry stale = new SteamAppParentEntry("4519120", "4009490", "Arctic Drive");
        stale.setResolvedAt(Instant.now().minus(31, ChronoUnit.DAYS));
        doReturn(java.util.Optional.of(stale)).when(steamAppParentRepository).findById("4519120");
        Map<String, Object> playtestBody = Map.of("4519120", Map.of(
            "success", true, "data", Map.of("type", "game", "name", "Arctic Drive Playtest")));
        givenSteamResponse(playtestBody);
        doReturn(java.util.Optional.empty()).when(redirectResolver).resolveParentAppId("4519120");

        assertThat(service.resolveEffectiveApp("4519120")).isEmpty();
        org.mockito.Mockito.verify(restClient, org.mockito.Mockito.atLeastOnce()).get();
    }
}
