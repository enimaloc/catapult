package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private ExperimentOverrideRepository experimentOverrideRepository;
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
}
