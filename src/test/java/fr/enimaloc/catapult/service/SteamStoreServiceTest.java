package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SteamStoreServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private SteamStoreService service;

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
}
