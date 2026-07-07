package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MinecraftServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private MinecraftService service;

    @BeforeEach
    void setupRestClientChain() {
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(any(URI.class));
        doReturn(headersSpec).when(headersSpec).accept(any());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void lookupProfile_found_returnsProfileWithDashedId() {
        doReturn(new MinecraftService.ProfileLookup("069a79f444e94726a5befca90e38aaf5", "jeb_"))
                .when(responseSpec).body(MinecraftService.ProfileLookup.class);

        var result = service.lookupProfile("jeb_");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("jeb_");
        assertThat(result.get().dashedId()).isEqualTo("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    }

    @Test
    void lookupProfile_notFound_returnsEmpty() {
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
                .when(responseSpec).body(MinecraftService.ProfileLookup.class);

        assertThat(service.lookupProfile("nexistepas")).isEmpty();
    }

    @Test
    void profileLookup_dashedId_keepsAlreadyDashedId() {
        var lookup = new MinecraftService.ProfileLookup("069a79f4-44e9-4726-a5be-fca90e38aaf5", "jeb_");
        assertThat(lookup.dashedId()).isEqualTo("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    }
}
