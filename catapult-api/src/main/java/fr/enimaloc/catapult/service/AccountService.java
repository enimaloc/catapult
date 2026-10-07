package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentEventRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentFeedbackRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.feedback.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.repository.twitch.CatapultCategoryChangeStateRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatActionTokenRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final ExperimentOverrideRepository experimentOverrideRepository;
    private final ExperimentAssignmentRepository experimentAssignmentRepository;
    private final ExperimentEventRepository experimentEventRepository;
    private final ExperimentFeedbackRepository experimentFeedbackRepository;
    private final FeedbackSubmissionRepository feedbackSubmissionRepository;
    private final GameBindingRepository gameBindingRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final TwitchatWidgetSettingsRepository twitchatWidgetSettingsRepository;
    private final CatapultCategoryChangeStateRepository catapultCategoryChangeStateRepository;
    private final TwitchatActionTokenRepository twitchatActionTokenRepository;
    private final fr.enimaloc.catapult.repository.twitchat.TwitchatActivePresetRepository twitchatActivePresetRepository;
    private final fr.enimaloc.catapult.repository.twitchat.TwitchatPayloadPresetRepository twitchatPayloadPresetRepository;
    private final Optional<XboxUserTokenService> xboxUserTokenService;
    private final BotToggleService botToggleService;
    private final TwitchChatService twitchChatService;
    private final EventSubService twitchEventSubService;

    @Value("${app.account.deletion-delay-days:7}")
    private int deletionDelayDays;

    // =========================================================
    // Suppression en deux temps
    // =========================================================

    @Transactional
    public void initiateAccountDeletion(UserAccount account) {
        account.setStatus(UserAccount.Status.PENDING_DELETION);
        account.setDeletionRequestedAt(Instant.now());
        botToggleService.setBotEnabled(account, false);
        log.info("Account {} marked as PENDING_DELETION", account.getId());
    }

    @Transactional
    public void cancelAccountDeletion(UserAccount account) {
        account.setStatus(UserAccount.Status.ACTIVE);
        account.setDeletionRequestedAt(null);
        botToggleService.setBotEnabled(account, true);
        log.info("Account {} deletion cancelled", account.getId());
    }

    @Transactional
    public void deleteAccountImmediately(UserAccount account) {
        deleteAccountPermanently(account);
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
        twitchChatService.disconnect(account);
        twitchEventSubService.disconnect(account);
        revokeTwitchToken(account);
        experimentOverrideRepository.deleteByTargetUser(account);
        experimentAssignmentRepository.deleteByUser(account);
        experimentEventRepository.deleteByUser(account);
        experimentFeedbackRepository.deleteByUser(account);
        feedbackSubmissionRepository.deleteByUser(account);
        gameBindingRepository.deleteByUser(account);
        getterConfigRepository.deleteByUser(account);
        userSettingsRepository.deleteByUser(account);
        twitchatWidgetSettingsRepository.deleteByUser(account);
        catapultCategoryChangeStateRepository.deleteByUser(account);
        twitchatActionTokenRepository.deleteByUserId(account.getId());
        twitchatActivePresetRepository.deleteByUserId(account.getId());
        twitchatPayloadPresetRepository.deleteByUser(account);
        // Pas d'endpoint de révocation officiel pour ces providers — suppression en base uniquement
//        for (OAuthToken.Provider p : List.of(OAuthToken.Provider.XBOX, OAuthToken.Provider.BATTLENET)) {
//            oAuthTokenRepository.findByUserAndProvider(account, p)
//                .ifPresent(oAuthTokenRepository::delete);
//        }

        userAccountRepository.delete(account);
        log.info("Account {} permanently deleted", account.getId());
    }

    private void revokeTwitchToken(UserAccount account) {
        oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH).ifPresent(token -> {
            try {
                // La révocation effective est gérée par TwitchService/DiscordService
                oAuthTokenRepository.delete(token);
            } catch (Exception e) {
                log.warn("Failed to revoke TWITCH token for account {}", account.getId(), e);
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
        if (provider == OAuthToken.Provider.STEAM) {
            account.setSteamId(null);
            userAccountRepository.save(account);
        }
        if (provider == OAuthToken.Provider.XBOX) {
            xboxUserTokenService.ifPresent(service -> service.evict(account.getId()));
            getterConfigRepository.findByUserAndProvider(account, GetterConfig.Provider.XBOX)
                .ifPresent(config -> {
                    config.setEnabled(false);
                    getterConfigRepository.save(config);
                });
        }
        log.info("Provider {} disconnected for account {}", provider, account.getId());
    }

    @Transactional
    public void unlinkTwitch(UserAccount account) {
        if (account.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot unlink Twitch from an account that is not ACTIVE");
        }
        oAuthTokenRepository.findByUserAndProvider(account, OAuthToken.Provider.TWITCH)
            .ifPresent(oAuthTokenRepository::delete);
        account.setTwitchId(null);
        account.setTwitchUsername(null);
        account.setProfileImageUrl(null);
        account.setStatus(UserAccount.Status.INACTIVE);
        botToggleService.setBotEnabled(account, false);
        log.info("Admin unlinked Twitch for account {}", account.getId());
    }
}
