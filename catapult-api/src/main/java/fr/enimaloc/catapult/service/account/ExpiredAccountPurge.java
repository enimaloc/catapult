package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes the accounts whose deletion was requested longer ago than the delay. Each goes in a transaction of its own
 * ({@link AccountService#deleteAccountImmediately}): a foreign key refusing one only shows at its commit, and must not
 * take the others back with it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiredAccountPurge {

    private final UserAccountRepository userAccountRepository;
    private final AccountService accountService;

    @Value("${app.account.deletion-delay-days:7}")
    private int deletionDelayDays;

    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredAccounts() {
        Instant cutoff = Instant.now().minus(deletionDelayDays, ChronoUnit.DAYS);
        for (UserAccount account : userAccountRepository
                .findByStatusAndDeletionRequestedAtBefore(UserAccount.Status.PENDING_DELETION, cutoff)) {
            try {
                accountService.deleteAccountImmediately(account);
            } catch (Exception e) {
                log.error("Failed to permanently delete account {}", account.getId(), e);
            }
        }
    }
}
