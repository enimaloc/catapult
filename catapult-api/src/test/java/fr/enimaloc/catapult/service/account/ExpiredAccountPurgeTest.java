package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpiredAccountPurgeTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final AccountService accountService = mock(AccountService.class);
    private final ExpiredAccountPurge purge = new ExpiredAccountPurge(accounts, accountService);

    @Test
    void anAccountRefused_doesNotStopTheNextOne() {
        UserAccount refused = new UserAccount();
        UserAccount next = new UserAccount();
        when(accounts.findByStatusAndDeletionRequestedAtBefore(eq(UserAccount.Status.PENDING_DELETION), any()))
                .thenReturn(List.of(refused, next));
        doThrow(new DataIntegrityViolationException("fk")).when(accountService).deleteAccountImmediately(refused);

        purge.purgeExpiredAccounts();

        verify(accountService).deleteAccountImmediately(next);
    }
}
