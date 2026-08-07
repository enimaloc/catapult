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
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAdminProviderSteamControllerTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private ApiAdminProviderSteamController controller;
    private final RawProviderResponseSupport rawSupport =
            new RawProviderResponseSupport(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "rawSupport", rawSupport);
        doReturn(getSpec).when(restClient).get();
        doReturn(getSpec).when(getSpec).uri(any(String.class));
        doReturn(responseSpec).when(getSpec).retrieve();
    }

    @Test
    void appdetails_success_returnsRawPrettyJson() {
        doReturn("{\"440\":{\"success\":true}}").when(responseSpec).body(String.class);

        var result = controller.appdetails("440");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"success\" : true");
        assertThat(result.hasError()).isFalse();
    }
}
