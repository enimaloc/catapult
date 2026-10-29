package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * {@link SystemTwitchAccountService} with the system bot's token refresh simulated, in the global
 * {@code twitch.app.bot-refresh} mode (the bot belongs to no streamer). A subclass for the same reason as
 * {@link SimulatedTwitchTokenService}: {@code getAccessToken} reaches {@link #refresh} through {@code this}.
 * <p>
 * Its moderator status check ({@link #check}) is asked on the streamer's channel: it follows the account's
 * {@code twitch.helix.bot-mod-status} mode, and answers modded when simulated.
 * <p>
 * A failure answers empty, as the real refresh does; a success keeps the stored token. An unlinked bot, or one
 * without a refresh token, goes to the real method, which answers empty without a call.
 */
@Slf4j
@Primary
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedSystemTwitchAccountService extends SystemTwitchAccountService {

    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository tokenRepo;
    private final TokenEncryptionService encryption;
    private final SimulatedCallRunner runner;

    public SimulatedSystemTwitchAccountService(UserAccountRepository userAccountRepository,
                                               OAuthTokenRepository tokenRepo, TokenEncryptionService encryption,
                                               RestClient restClient, SimulatedCallRunner runner) {
        super(userAccountRepository, tokenRepo, encryption, restClient);
        this.userAccountRepository = userAccountRepository;
        this.tokenRepo = tokenRepo;
        this.encryption = encryption;
        this.runner = runner;
    }

    /**
     * The bot's moderator status on the channel, only asked to Helix while the account's
     * {@code twitch.helix.bot-mod-status} is {@code REAL} (never for a fictitious account); otherwise modded, so the
     * simulated channel shows no "make the bot a moderator" banner. A simulated failure answers not modded, as the
     * real check does when it cannot tell.
     */
    @Override
    public BotModStatus check(UserAccount user, String streamerToken) {
        try {
            return runner.run(user.getId(), SimulatedCalls.TWITCH_HELIX_BOT_MOD_STATUS,
                    "GET /moderation/moderators bot status", () -> super.check(user, streamerToken),
                    () -> BotModStatus.ofModded(true), SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Simulated bot mod status check failed for user {}: {}", user.getId(), e.getMessage());
            return BotModStatus.ofModded(false);
        }
    }

    @Override
    public synchronized Optional<String> refresh() {
        Optional<OAuthToken> token = refreshableBotToken();
        if (token.isEmpty()) {
            return super.refresh();
        }
        try {
            return runner.run(null, SimulatedCalls.TWITCH_APP_BOT_REFRESH, "POST /oauth2/token system bot refresh",
                    super::refresh, () -> Optional.of(encryption.decrypt(token.get().getAccessToken())),
                    SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.error("System bot Twitch refresh failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<OAuthToken> refreshableBotToken() {
        return userAccountRepository.findBySystemAccountTrue()
                .filter(account -> account.getTwitchId() != null)
                .flatMap(account -> tokenRepo.findByUserAndProvider(account, OAuthToken.Provider.TWITCH))
                .filter(token -> token.getRefreshToken() != null);
    }
}
