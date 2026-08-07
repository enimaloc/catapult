package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAdminProviderGitLabControllerTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private ApiAdminProviderGitLabController controller;
    private final RawProviderResponseSupport rawSupport =
            new RawProviderResponseSupport(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "rawSupport", rawSupport);
        ReflectionTestUtils.setField(controller, "baseUrl", "https://git.enimaloc.fr");
        ReflectionTestUtils.setField(controller, "token", "glpat-test");
        ReflectionTestUtils.setField(controller, "projectId", "1");
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(any(String.class));
        doReturn(headersSpec).when(headersSpec).header(any(), any());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void issue_success_returnsRawPrettyJson() {
        doReturn("{\"iid\":42,\"state\":\"opened\"}").when(responseSpec).body(String.class);

        var result = controller.issue(42);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"state\" : \"opened\"");
    }
}
