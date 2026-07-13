package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MsaAuthClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private MsaAuthClient client;

    @BeforeEach
    void setup() {
        doReturn(postSpec).when(restClient).post();
        doReturn(bodySpec).when(postSpec).uri(any(URI.class));
        // body(Object) : matcher explicite sinon Mockito choisit l'overload body(Class) -> NPE
        doReturn(bodySpec).when(bodySpec).body(any(Object.class));
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).accept(any());
        doReturn(responseSpec).when(bodySpec).retrieve();
        client = new MsaAuthClient(restClient, "client-id-test");
    }

    @Test
    void refresh_sendsFormEncodedGrantAndMapsResponse() {
        doReturn(new MsaAuthClient.MsaTokens("access-1", "refresh-2", 3600L))
                .when(responseSpec).body(MsaAuthClient.MsaTokens.class);

        var tokens = client.refresh("refresh-1");

        assertThat(tokens.accessToken()).isEqualTo("access-1");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-2");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).body(body.capture());
        MultiValueMap<String, String> form = (MultiValueMap<String, String>) body.getValue();
        assertThat(form.getFirst("grant_type")).isEqualTo("refresh_token");
        assertThat(form.getFirst("refresh_token")).isEqualTo("refresh-1");
        assertThat(form.getFirst("client_id")).isEqualTo("client-id-test");
        assertThat(form.getFirst("scope")).isEqualTo("XboxLive.signin offline_access");
    }

    @Test
    void pollDeviceCode_authorizationPending_returnsEmpty() {
        doThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null,
                "{\"error\":\"authorization_pending\"}".getBytes(), null))
                .when(responseSpec).body(MsaAuthClient.MsaTokens.class);

        assertThat(client.pollDeviceCode("device-1")).isEmpty();
    }

    @Test
    void pollDeviceCode_otherError_throws() {
        doThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null,
                "{\"error\":\"expired_token\"}".getBytes(), null))
                .when(responseSpec).body(MsaAuthClient.MsaTokens.class);

        assertThatThrownBy(() -> client.pollDeviceCode("device-1"))
                .isInstanceOf(MsaAuthClient.MsaAuthException.class);
    }
}
