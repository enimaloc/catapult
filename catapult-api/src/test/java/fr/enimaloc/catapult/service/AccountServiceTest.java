package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.CatapultCategoryChangeStateRepository;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.ExperimentEventRepository;
import fr.enimaloc.catapult.repository.ExperimentFeedbackRepository;
import fr.enimaloc.catapult.repository.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.TwitchatActionTokenRepository;
import fr.enimaloc.catapult.repository.TwitchatActivePresetRepository;
import fr.enimaloc.catapult.repository.TwitchatPayloadPresetRepository;
import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
    @Mock private TwitchatWidgetSettingsRepository twitchatWidgetSettingsRepository;
    @Mock private CatapultCategoryChangeStateRepository catapultCategoryChangeStateRepository;
    @Mock private TwitchatActionTokenRepository twitchatActionTokenRepository;
    @Mock private TwitchatActivePresetRepository twitchatActivePresetRepository;
    @Mock private TwitchatPayloadPresetRepository twitchatPayloadPresetRepository;
    @Mock private BotToggleService botToggleService;
    @Mock private TwitchChatService twitchChatService;
    @Mock private EventSubService twitchEventSubService;
    @InjectMocks private AccountService accountService;

    private UserAccount account;

    @BeforeEach
    void setup() {
        account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId("twitch123");
        account.setTwitchUsername("streamer");
        account.setStatus(UserAccount.Status.ACTIVE);
        account.setProfileImageUrl("https://static-cdn.jtvnw.net/jtv_user_pictures/avatar.jpg");
        account.setBotEnabled(true);
    }

    @Test
    void initiateAccountDeletion_setsPendingDeletionAndDisablesBot() {
        accountService.initiateAccountDeletion(account);

        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.PENDING_DELETION);
        assertThat(account.getDeletionRequestedAt()).isNotNull();
        verify(botToggleService).setBotEnabled(account, false);
    }

    @Test
    void cancelAccountDeletion_reactivatesAndReenablesBot() {
        account.setStatus(UserAccount.Status.PENDING_DELETION);

        accountService.cancelAccountDeletion(account);

        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(account.getDeletionRequestedAt()).isNull();
        verify(botToggleService).setBotEnabled(account, true);
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
        verify(twitchChatService).disconnect(account);
        verify(twitchEventSubService).disconnect(account);
    }

    @Test
    void deleteAccountImmediately_deletesTwitchatDependentRows() {
        when(oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        accountService.deleteAccountImmediately(account);

        InOrder order = inOrder(twitchatWidgetSettingsRepository, catapultCategoryChangeStateRepository,
            twitchatActionTokenRepository, twitchatActivePresetRepository, twitchatPayloadPresetRepository,
            userAccountRepository);
        order.verify(twitchatWidgetSettingsRepository).deleteByUser(account);
        order.verify(catapultCategoryChangeStateRepository).deleteByUser(account);
        order.verify(twitchatActionTokenRepository).deleteByUserId(account.getId());
        order.verify(twitchatActivePresetRepository).deleteByUserId(account.getId());
        order.verify(twitchatPayloadPresetRepository).deleteByUser(account);
        order.verify(userAccountRepository).delete(account);
    }

    @Test
    void deleteAccountImmediately_noTwitchToken_stillDeletesUser() {
        when(oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        accountService.deleteAccountImmediately(account);

        verify(oAuthTokenRepository, never()).delete(any(OAuthToken.class));
        verify(experimentOverrideRepository).deleteByTargetUser(account);
        verify(userAccountRepository).delete(account);
        verify(twitchChatService).disconnect(account);
        verify(twitchEventSubService).disconnect(account);
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
        assertThat(account.getProfileImageUrl()).isNull();
        verify(botToggleService).setBotEnabled(account, false);
    }

    @Test
    void unlinkTwitch_noToken_stillSetsInactiveAndNullsFields() {
        when(oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        accountService.unlinkTwitch(account);

        verify(oAuthTokenRepository, never()).delete(any(OAuthToken.class));
        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.INACTIVE);
        assertThat(account.getTwitchId()).isNull();
        assertThat(account.getTwitchUsername()).isNull();
        assertThat(account.getProfileImageUrl()).isNull();
        verify(botToggleService).setBotEnabled(account, false);
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
