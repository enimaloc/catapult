package fr.enimaloc.catapult.getter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SteamPlaytestRedirectResolverTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private SteamPlaytestRedirectResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SteamPlaytestRedirectResolver(restClient);
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void resolveParentAppId_redirectToParent_returnsParentId() {
        ResponseEntity<Void> response = ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "https://store.steampowered.com/app/4009490")
            .build();
        doReturn(response).when(responseSpec).toBodilessEntity();

        assertThat(resolver.resolveParentAppId("4519120")).contains("4009490");
    }

    @Test
    void resolveParentAppId_noRedirect_returnsEmpty() {
        ResponseEntity<Void> response = ResponseEntity.ok().build();
        doReturn(response).when(responseSpec).toBodilessEntity();

        assertThat(resolver.resolveParentAppId("730")).isEmpty();
    }

    @Test
    void resolveParentAppId_redirectWithoutLocationHeader_returnsEmpty() {
        ResponseEntity<Void> response = ResponseEntity.status(HttpStatus.FOUND).build();
        doReturn(response).when(responseSpec).toBodilessEntity();

        assertThat(resolver.resolveParentAppId("4519120")).isEmpty();
    }

    @Test
    void resolveParentAppId_redirectToSameAppId_returnsEmpty() {
        ResponseEntity<Void> response = ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "https://store.steampowered.com/app/4519120")
            .build();
        doReturn(response).when(responseSpec).toBodilessEntity();

        assertThat(resolver.resolveParentAppId("4519120")).isEmpty();
    }

    @Test
    void resolveParentAppId_networkFailure_returnsEmpty() {
        when(getSpec.uri(anyString(), anyString())).thenThrow(new RuntimeException("network down"));

        assertThat(resolver.resolveParentAppId("4519120")).isEmpty();
    }
}
