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

        var result = controller.appdetails("440", null, null);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"success\" : true");
        assertThat(result.hasError()).isFalse();
    }

    @Test
    void appdetails_withCcAndLanguage_appendsQueryParams() {
        doReturn("{\"440\":{\"success\":true}}").when(responseSpec).body(String.class);

        var result = controller.appdetails("440", "fr", "french");

        assertThat(result.status()).isEqualTo(200);
        org.mockito.ArgumentCaptor<String> uriCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(getSpec).uri(uriCaptor.capture());
        assertThat(uriCaptor.getValue()).contains("cc=fr").contains("l=french");
    }

    @Test
    void appdetails_withoutCcAndLanguage_omitsQueryParams() {
        doReturn("{\"440\":{\"success\":true}}").when(responseSpec).body(String.class);

        var result = controller.appdetails("440", null, null);

        assertThat(result.status()).isEqualTo(200);
        org.mockito.ArgumentCaptor<String> uriCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(getSpec).uri(uriCaptor.capture());
        assertThat(uriCaptor.getValue()).doesNotContain("cc=").doesNotContain("l=");
    }
}
