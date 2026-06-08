package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.event.TwitchLoginEvent;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Saves the Twitch refresh token after a successful OAuth2 login.
 * Spring's OAuth2UserService only exposes the access token; the refresh token
 * is only available via OAuth2AuthorizedClientRepository once the client is saved,
 * which happens before this handler is called.
 */
@Slf4j
@Component
@Profile("!mock-web")
@RequiredArgsConstructor
public class TwitchLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final JwtService jwtService;
    private final AuthCodeStore codeStore;

    @Value("${app.web-url:}")
    private String webUrl;

    private final SavedRequestAwareAuthenticationSuccessHandler delegate =
        new SavedRequestAwareAuthenticationSuccessHandler();

    @PostConstruct
    void init() {
        delegate.setDefaultTargetUrl("/dashboard");
        delegate.setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (authentication instanceof OAuth2AuthenticationToken oauth2Token
            && "twitch".equals(oauth2Token.getAuthorizedClientRegistrationId())
            && oauth2Token.getPrincipal() instanceof CatapultOAuth2User catUser) {

            eventPublisher.publishEvent(new TwitchLoginEvent(this, catUser.getUserAccount()));

            OAuth2AuthorizedClient client = authorizedClientRepository
                .loadAuthorizedClient("twitch", authentication, request);

            if (client != null && client.getRefreshToken() != null) {
                oAuthTokenRepository.findByUserAndProvider(catUser.getUserAccount(), OAuthToken.Provider.TWITCH)
                    .ifPresent(token -> {
                        token.setRefreshToken(tokenEncryptionService.encrypt(
                            client.getRefreshToken().getTokenValue()));
                        if (client.getAccessToken().getExpiresAt() != null) {
                            token.setExpiresAt(client.getAccessToken().getExpiresAt());
                        }
                        oAuthTokenRepository.save(token);
                        log.debug("Saved Twitch refresh token for user {}", catUser.getUserAccount().getId());
                    });
            } else {
                log.warn("No refresh token in OAuth2AuthorizedClient for user {} — Twitch may not have issued one",
                    catUser.getUserAccount().getId());
            }

            // If catapult-web is configured, redirect there with a one-time code instead of the JWT.
            // catapult-web exchanges the code server-to-server via POST /api/auth/exchange (30s TTL, single-use).
            if (webUrl != null && !webUrl.isBlank()) {
                String jwt = jwtService.generate(catUser);
                String code = codeStore.issue(jwt);
                String callbackUrl = UriComponentsBuilder.fromUriString(webUrl)
                        .path("/auth/callback")
                        .queryParam("code", code)
                        .build().toUriString();
                response.sendRedirect(callbackUrl);
                return;
            }
        }

        delegate.onAuthenticationSuccess(request, response, authentication);
    }
}
