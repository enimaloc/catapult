package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link TwitchTokenService} with its two token-endpoint calls simulated: a streamer's token refresh
 * ({@code twitch.helix.refresh-token}, the account's mode) and the app token ({@code twitch.app.app-token}, global).
 * <p>
 * A subclass rather than a decorator: the class has no interface, and its own {@link #resolveAccessToken} reaches
 * the refresh through {@code this}, which only an override intercepts. Every caller keeps the real failure path:
 * a simulated refresh failure answers {@code null}, as the real one does, so {@code TwitchServiceImpl} and the
 * simulated Twitch service pause the bot. A simulated successful refresh changes nothing and answers the token
 * already stored: no made-up token ever reaches a real call.
 */
@Slf4j
@Primary
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedTwitchTokenService extends TwitchTokenService {

    /** What a simulated refresh answers for an account that has no stored token (a fictitious one). */
    static final String SIMULATED_ACCESS_TOKEN = "simulated-access-token";
    static final String SIMULATED_APP_TOKEN = "simulated-app-token";

    private final TokenEncryptionService tokenEncryptionService;
    private final SimulatedCallRunner runner;

    public SimulatedTwitchTokenService(OAuthTokenRepository oAuthTokenRepository,
                                       TokenEncryptionService tokenEncryptionService, RestClient restClient,
                                       SimulatedCallRunner runner) {
        super(oAuthTokenRepository, tokenEncryptionService, restClient);
        this.tokenEncryptionService = tokenEncryptionService;
        this.runner = runner;
    }

    /**
     * The refresh, in the account's {@code twitch.helix.refresh-token} mode. {@code token} may be {@code null} for
     * a fictitious account, which never runs for real; a token without a refresh token fails as the real one does,
     * without a call.
     */
    @Override
    public @Nullable String refreshAccessToken(@Nullable OAuthToken token, UserAccount user) {
        if (token != null && token.getRefreshToken() == null) {
            return super.refreshAccessToken(token, user);
        }
        try {
            return runner.run(user.getId(), SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN, "POST /oauth2/token refresh",
                    () -> super.refreshAccessToken(token, user), () -> currentAccessToken(token),
                    SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Failed to refresh Twitch token for user {}: {}", user.getId(), e.getMessage());
            return null;
        }
    }

    /** The app token, in the global {@code twitch.app.app-token} mode; a failure is thrown, as the real one is. */
    @Override
    public String getAppAccessToken() {
        return runner.run(null, SimulatedCalls.TWITCH_APP_APP_TOKEN, "POST /oauth2/token client_credentials",
                super::getAppAccessToken, () -> SIMULATED_APP_TOKEN, SimulatedCallRunner::httpError);
    }

    private String currentAccessToken(@Nullable OAuthToken token) {
        return token != null && token.getAccessToken() != null
                ? tokenEncryptionService.decrypt(token.getAccessToken())
                : SIMULATED_ACCESS_TOKEN;
    }
}
