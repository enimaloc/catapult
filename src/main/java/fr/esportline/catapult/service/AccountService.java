package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository oAuthTokenRepository;

    @Value("${app.account.deletion-delay-days:7}")
    private int deletionDelayDays;

    // =========================================================
    // Suppression en deux temps
    // =========================================================

    @Transactional
    public void initiateAccountDeletion(UserAccount account) {
        account.setStatus(UserAccount.Status.PENDING_DELETION);
        account.setDeletionRequestedAt(Instant.now());
        account.setBotEnabled(false);
        userAccountRepository.save(account);
        log.info("Account {} marked as PENDING_DELETION", account.getId());
    }

    @Transactional
    public void cancelAccountDeletion(UserAccount account) {
        account.setStatus(UserAccount.Status.ACTIVE);
        account.setDeletionRequestedAt(null);
        account.setBotEnabled(true);
        userAccountRepository.save(account);
        log.info("Account {} deletion cancelled", account.getId());
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void purgeExpiredAccounts() {
        Instant cutoff = Instant.now().minus(deletionDelayDays, ChronoUnit.DAYS);
        List<UserAccount> toDelete = userAccountRepository
            .findByStatusAndDeletionRequestedAtBefore(UserAccount.Status.PENDING_DELETION, cutoff);

        for (UserAccount account : toDelete) {
            try {
                deleteAccountPermanently(account);
            } catch (Exception e) {
                log.error("Failed to permanently delete account {}", account.getId(), e);
            }
        }
    }

    private void deleteAccountPermanently(UserAccount account) {
        revokeToken(account, OAuthToken.Provider.TWITCH);
        // Pas d'endpoint de révocation officiel pour ces providers — suppression en base uniquement
        for (OAuthToken.Provider p : List.of(OAuthToken.Provider.XBOX, OAuthToken.Provider.BATTLENET)) {
            oAuthTokenRepository.findByUserAndProvider(account, p)
                .ifPresent(oAuthTokenRepository::delete);
        }

        userAccountRepository.delete(account);
        log.info("Account {} permanently deleted", account.getId());
    }

    private void revokeToken(UserAccount account, OAuthToken.Provider provider) {
        oAuthTokenRepository.findByUserAndProvider(account, provider).ifPresent(token -> {
            try {
                // La révocation effective est gérée par TwitchService/DiscordService
                oAuthTokenRepository.delete(token);
            } catch (Exception e) {
                log.warn("Failed to revoke {} token for account {}", provider, account.getId(), e);
            }
        });
    }

    // =========================================================
    // Déconnexion provider secondaire
    // =========================================================

    @Transactional
    public void disconnectProvider(UserAccount account, OAuthToken.Provider provider) {
        if (provider == OAuthToken.Provider.TWITCH) {
            throw new IllegalArgumentException("Cannot disconnect primary Twitch provider");
        }
        oAuthTokenRepository.findByUserAndProvider(account, provider)
            .ifPresent(oAuthTokenRepository::delete);
        log.info("Provider {} disconnected for account {}", provider, account.getId());
    }
}
