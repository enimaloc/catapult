package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class TwitchAccountValidationServiceTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TwitchTokenService twitchTokenService;
    @Mock private RestClient restClient;

    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private TwitchAccountValidationService service;

    @BeforeEach
    void setUp() {
        service = new TwitchAccountValidationService(userAccountRepository, twitchTokenService, restClient);
        ReflectionTestUtils.setField(service, "twitchClientId", "client-id");
    }

    private UserAccount userWith(String twitchId) {
        UserAccount u = new UserAccount();
        u.setId(UUID.randomUUID());
        u.setTwitchId(twitchId);
        u.setTwitchUsername("user_" + twitchId);
        u.setStatus(UserAccount.Status.ACTIVE);
        return u;
    }

    @Test
    void validate_marksAbsentUserInactive() {
        UserAccount present = userWith("111");
        UserAccount absent = userWith("222");
        when(userAccountRepository.findByStatusAndTwitchIdNotNull(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(present, absent));
        when(twitchTokenService.getAppAccessToken()).thenReturn("app-token");

        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(
            Map.of("data", List.of(Map.of("id", "111", "login", "user_111", "profile_image_url", "")))
        );

        service.validateAccounts();

        assertThat(absent.getStatus()).isEqualTo(UserAccount.Status.INACTIVE);
        assertThat(present.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        verify(userAccountRepository).saveAll(List.of(absent));
    }

    @Test
    void validate_apiError_skipsAndDoesNotMarkInactive() {
        UserAccount u = userWith("333");
        when(userAccountRepository.findByStatusAndTwitchIdNotNull(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u));
        when(twitchTokenService.getAppAccessToken()).thenReturn("app-token");

        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenThrow(new RuntimeException("network error"));

        service.validateAccounts();

        assertThat(u.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        verify(userAccountRepository, never()).saveAll(anyList());
    }

    @Test
    void validate_nullApiResponse_skipsAndDoesNotMarkInactive() {
        UserAccount u = userWith("555");
        when(userAccountRepository.findByStatusAndTwitchIdNotNull(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u));
        when(twitchTokenService.getAppAccessToken()).thenReturn("app-token");

        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(null);

        service.validateAccounts();

        assertThat(u.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        verify(userAccountRepository, never()).saveAll(anyList());
    }

    @Test
    void validate_updatesUsernameAndAvatarWhenChanged() {
        UserAccount u = userWith("444");
        u.setTwitchUsername("old_name");
        u.setProfileImageUrl("old-url");
        when(userAccountRepository.findByStatusAndTwitchIdNotNull(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u));
        when(twitchTokenService.getAppAccessToken()).thenReturn("app-token");

        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("data",
            List.of(Map.of("id", "444", "login", "new_name", "profile_image_url", "new-url"))
        ));

        service.validateAccounts();

        assertThat(u.getTwitchUsername()).isEqualTo("new_name");
        assertThat(u.getProfileImageUrl()).isEqualTo("new-url");
        verify(userAccountRepository).saveAll(List.of(u));
    }
}
