package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.service.IgdbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAdminProviderIgdbControllerTest {

    @Mock private IgdbService igdbService;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private ApiAdminProviderIgdbController controller;
    private final RawProviderResponseSupport rawSupport =
            new RawProviderResponseSupport(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "rawSupport", rawSupport);
        ReflectionTestUtils.setField(controller, "clientId", "test-client-id");
        doReturn(postSpec).when(restClient).post();
        doReturn(bodySpec).when(postSpec).uri(any(String.class));
        doReturn(bodySpec).when(bodySpec).header(any(), any());
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).body(any(Object.class));
        doReturn(responseSpec).when(bodySpec).retrieve();
    }

    @Test
    void query_success_returnsRawPrettyJson() {
        when(igdbService.getAppToken()).thenReturn("app-token");
        doReturn("[{\"id\":1,\"name\":\"Celeste\"}]").when(responseSpec).body(String.class);

        var result = controller.query(new ApiAdminProviderIgdbController.QueryRequest("games", "fields name; where id=1;"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"name\" : \"Celeste\"");
    }

    @Test
    void query_invalidEndpoint_throws400() {
        assertThatThrownBy(() -> controller.query(new ApiAdminProviderIgdbController.QueryRequest("Games!", "fields name;")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid endpoint");
    }

    @Test
    void query_blankAppToken_returnsErrorWithoutCallingRestClient() {
        when(igdbService.getAppToken()).thenReturn("");

        var result = controller.query(new ApiAdminProviderIgdbController.QueryRequest("games", "fields name;"));

        assertThat(result.error()).contains("IGDB token not available");
        org.mockito.Mockito.verifyNoInteractions(restClient);
    }
}
