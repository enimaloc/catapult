package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.ExperimentEventRepository;
import fr.enimaloc.catapult.repository.ExperimentFeedbackRepository;
import fr.enimaloc.catapult.repository.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private ExperimentOverrideRepository experimentOverrideRepository;
    @Mock private ExperimentAssignmentRepository experimentAssignmentRepository;
    @Mock private ExperimentEventRepository experimentEventRepository;
    @Mock private ExperimentFeedbackRepository experimentFeedbackRepository;
    @Mock private FeedbackSubmissionRepository feedbackSubmissionRepository;
    @Mock private GameBindingRepository gameBindingRepository;
    @Mock private GetterConfigRepository getterConfigRepository;
    @Mock private UserSettingsRepository userSettingsRepository;
    @InjectMocks private AccountService accountService;

    private UserAccount account;

    @BeforeEach
    void setup() {
        account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId("twitch123");
        account.setTwitchUsername("streamer");
        account.setStatus(UserAccount.Status.ACTIVE);
    }

    @Test
    void deleteAccountImmediately_deletesUserAndRevokesTwitchToken() {
        OAuthToken token = new OAuthToken();
        token.setId(UUID.randomUUID());
        when(oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));

        accountService.deleteAccountImmediately(account);

        verify(oAuthTokenRepository).delete(token);
        verify(experimentOverrideRepository).deleteByTargetUser(account);
        verify(userAccountRepository).delete(account);
    }

    @Test
    void deleteAccountImmediately_noTwitchToken_stillDeletesUser() {
        when(oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        accountService.deleteAccountImmediately(account);

        verify(oAuthTokenRepository, never()).delete(any(OAuthToken.class));
        verify(experimentOverrideRepository).deleteByTargetUser(account);
        verify(userAccountRepository).delete(account);
    }

    @Test
    void unlinkTwitch_setsInactiveAndNullsFields() {
        OAuthToken token = new OAuthToken();
        token.setId(UUID.randomUUID());
        when(oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));

        accountService.unlinkTwitch(account);

        verify(oAuthTokenRepository).delete(token);
        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.INACTIVE);
        assertThat(account.getTwitchId()).isNull();
        assertThat(account.getTwitchUsername()).isNull();
        verify(userAccountRepository).save(account);
    }

    @Test
    void unlinkTwitch_alreadyInactive_throws400() {
        account.setStatus(UserAccount.Status.INACTIVE);

        assertThatThrownBy(() -> accountService.unlinkTwitch(account))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    void unlinkTwitch_pendingDeletion_throws400() {
        account.setStatus(UserAccount.Status.PENDING_DELETION);

        assertThatThrownBy(() -> accountService.unlinkTwitch(account))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userAccountRepository, never()).save(any());
    }
}
