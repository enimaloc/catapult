package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.MinecraftTokenService;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAdminProviderMinecraftControllerTest {

    @Mock private MinecraftTokenService tokenService;
    @Mock private MinecraftServiceAccountRepository accountRepository;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.RequestBodySpec postBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private ApiAdminProviderMinecraftController controller;
    private final RawProviderResponseSupport rawSupport =
            new RawProviderResponseSupport(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "rawSupport", rawSupport);
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(any(java.net.URI.class));
        doReturn(headersSpec).when(headersSpec).header(any(), any());
        doReturn(headersSpec).when(headersSpec).accept(any());
        doReturn(responseSpec).when(headersSpec).retrieve();

        doReturn(postSpec).when(restClient).post();
        doReturn(postBodySpec).when(postSpec).uri(any(java.net.URI.class));
        doReturn(postBodySpec).when(postBodySpec).header(any(), any());
        doReturn(postBodySpec).when(postBodySpec).contentType(any());
        doReturn(postBodySpec).when(postBodySpec).accept(any());
        doReturn(postBodySpec).when(postBodySpec).body(any(Object.class));
        doReturn(responseSpec).when(postBodySpec).retrieve();
    }

    @Test
    void profileLookup_public_returnsRawJson() {
        doReturn("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"jeb_\"}")
                .when(responseSpec).body(String.class);

        var result = controller.profileLookup("jeb_");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"name\" : \"jeb_\"");
    }

    @Test
    void friends_unknownAccountId_throws404() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.friends(accountId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void friends_tokenUnavailable_throws404() {
        UUID accountId = UUID.randomUUID();
        MinecraftServiceAccount account = new MinecraftServiceAccount();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(tokenService.getToken(account)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.friends(accountId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void friends_validAccount_returnsRawJson() {
        UUID accountId = UUID.randomUUID();
        MinecraftServiceAccount account = new MinecraftServiceAccount();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(tokenService.getToken(account)).thenReturn(Optional.of("mc-token"));
        doReturn("{\"friends\":[]}").when(responseSpec).body(String.class);

        var result = controller.friends(accountId);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"friends\"");
    }

    @Test
    void presence_defaultsToOnlineStatus() {
        UUID accountId = UUID.randomUUID();
        MinecraftServiceAccount account = new MinecraftServiceAccount();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(tokenService.getToken(account)).thenReturn(Optional.of("mc-token"));
        doReturn("{\"presence\":[]}").when(responseSpec).body(String.class);

        var result = controller.presence(accountId, "ONLINE");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"presence\"");
    }

    @Test
    void presence_invalidStatus_throws400() {
        UUID accountId = UUID.randomUUID();
        MinecraftServiceAccount account = new MinecraftServiceAccount();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(tokenService.getToken(account)).thenReturn(Optional.of("mc-token"));

        assertThatThrownBy(() -> controller.presence(accountId, "NOT_A_STATUS"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
