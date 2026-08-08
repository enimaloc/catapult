package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.XboxUserTokenService;
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
class ApiAdminProviderXboxControllerTest {

    @Mock private XboxUserTokenService tokenService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private ApiAdminProviderXboxController controller;
    private final RawProviderResponseSupport rawSupport =
            new RawProviderResponseSupport(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "rawSupport", rawSupport);
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(any(java.net.URI.class));
        doReturn(headersSpec).when(headersSpec).header(any(), any());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void presence_unknownUserId_throws404() {
        UUID userId = UUID.randomUUID();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.presence(userId, "all"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void presence_noXboxSessionAvailable_throws404() {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenService.getToken(user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.presence(userId, "all"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void presence_validSession_returnsRawJson() {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenService.getToken(user)).thenReturn(Optional.of(
                new XboxUserTokenService.XstsSession("xsts-token", "user-hash", "2533274999999999")));
        doReturn("{\"devices\":[]}").when(responseSpec).body(String.class);

        var result = controller.presence(userId, "all");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"devices\"");
    }

    @Test
    void presence_invalidLevel_throws400() {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenService.getToken(user)).thenReturn(Optional.of(
                new XboxUserTokenService.XstsSession("xsts-token", "user-hash", "2533274999999999")));

        assertThatThrownBy(() -> controller.presence(userId, "not_a_level"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void presence_customLevel_isUsedInRequestUri() {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenService.getToken(user)).thenReturn(Optional.of(
                new XboxUserTokenService.XstsSession("xsts-token", "user-hash", "2533274999999999")));
        doReturn("{\"devices\":[]}").when(responseSpec).body(String.class);

        var result = controller.presence(userId, "device");

        assertThat(result.status()).isEqualTo(200);
        org.mockito.ArgumentCaptor<java.net.URI> uriCaptor = org.mockito.ArgumentCaptor.forClass(java.net.URI.class);
        org.mockito.Mockito.verify(getSpec).uri(uriCaptor.capture());
        assertThat(uriCaptor.getValue().toString()).contains("level=device");
    }
}
