package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.event.TwitchLoginEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.bungie.BungieLinkService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
 * Saves the refresh token after a successful OAuth2 login or secondary link
 * (Twitch = login, Xbox and Battle.net = secondary links on an already-authenticated user).
 * Spring's OAuth2UserService only exposes the access token; the refresh token
 * is only available via OAuth2AuthorizedClientRepository once the client is saved,
 * which happens before this handler is called.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TwitchLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** Request attribute the World of Warcraft import leaves its character count in, for the redirect. */
    public static final String WOW_IMPORT_COUNT_ATTRIBUTE = "catapult.blizzard.wow-import-count";


    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final JwtService jwtService;
    private final AuthCodeStore codeStore;
    private final ObjectProvider<BungieLinkService> bungieLinks;

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
            && oauth2Token.getPrincipal() instanceof CatapultOAuth2User catUser) {

            String registrationId = oauth2Token.getAuthorizedClientRegistrationId();
            if ("twitch".equals(registrationId)) {
                eventPublisher.publishEvent(new TwitchLoginEvent(this, catUser.getUserAccount()));
                saveRefreshToken(request, authentication, catUser, "twitch", OAuthToken.Provider.TWITCH);

                // If catapult-web is configured, redirect there with a one-time code instead of the JWT.
                // catapult-web exchanges the code server-to-server via POST /api/internal/auth/exchange (30s TTL, single-use).
                // catapult-web's state comes back with it: its callback only completes a login its session started.
                if (webUrl != null && !webUrl.isBlank()) {
                    String jwt = jwtService.generate(catUser);
                    String code = codeStore.issue(jwt);
                    UriComponentsBuilder callback = UriComponentsBuilder.fromUriString(webUrl)
                            .path("/auth/callback")
                            .queryParam("code", code);
                    InviteCodeRelayFilter.pendingWebState(request).ifPresent(state -> callback.queryParam("state", state));
                    InviteCodeRelayFilter.clearPendingWebState(request, response);
                    response.sendRedirect(callback.encode().build().toUriString());
                    return;
                }
            } else if ("battlenet".equals(registrationId)) {
                // Battle.net is a secondary link that keeps no token (the bot befriends the account): the access
                // token Spring just stored is forgotten at once (spec §10.2), then the user goes back to their
                // channel's integrations tab.
                authorizedClientRepository.removeAuthorizedClient("battlenet", authentication, request, response);
                if (webUrl != null && !webUrl.isBlank()) {
                    response.sendRedirect(channelIntegrationsUrl(catUser));
                    return;
                }
            } else if ("battlenet-stats".equals(registrationId)) {
                // The World of Warcraft import read the characters with this token: it is forgotten at once, and
                // the card says the import is done.
                authorizedClientRepository.removeAuthorizedClient("battlenet-stats", authentication, request, response);
                if (webUrl != null && !webUrl.isBlank()) {
                    boolean none = Integer.valueOf(0).equals(request.getAttribute(WOW_IMPORT_COUNT_ATTRIBUTE));
                    response.sendRedirect(UriComponentsBuilder.fromUriString(channelIntegrationsUrl(catUser))
                            .replaceQueryParam("imported", none ? "wow-empty" : "wow").toUriString());
                    return;
                }
            } else if ("bungie".equals(registrationId)) {
                // The Bungie.net link: CatapultOAuth2UserService checked the account and left it pending; its
                // refresh token, only known here, is written encrypted, then Spring's copy is forgotten at once.
                boolean linked = completeBungieLink(request, response, authentication, catUser);
                if (webUrl != null && !webUrl.isBlank()) {
                    UriComponentsBuilder target = UriComponentsBuilder.fromUriString(channelIntegrationsUrl(catUser));
                    target = linked ? target.replaceQueryParam("linked", "bungie")
                            : target.replaceQueryParam("error", "bungie_failed");
                    response.sendRedirect(target.toUriString());
                    return;
                }
            } else if ("epic".equals(registrationId)) {
                // Like Battle.net: the bot befriends the account, so the EOS access token is forgotten at once.
                authorizedClientRepository.removeAuthorizedClient("epic", authentication, request, response);
                if (webUrl != null && !webUrl.isBlank()) {
                    response.sendRedirect(channelIntegrationsUrl(catUser));
                    return;
                }
            } else if ("xbox".equals(registrationId)) {
                // Xbox is a secondary link: the user is already authenticated via Twitch.
                // Only the refresh token needs saving — no login event, no JWT re-issuance.
                saveRefreshToken(request, authentication, catUser, "xbox", OAuthToken.Provider.XBOX);

                // Send the user back to their channel's integrations tab on catapult-web, where
                // they started, not the /dashboard default meant for the primary Twitch login on
                // catapult-api itself.
                if (webUrl != null && !webUrl.isBlank()) {
                    response.sendRedirect(channelIntegrationsUrl(catUser));
                    return;
                }
            }
        }

        delegate.onAuthenticationSuccess(request, response, authentication);
    }

    /** Writes the pending Bungie link with its refresh token; false when it could not be (never logging a token). */
    private boolean completeBungieLink(HttpServletRequest request, HttpServletResponse response,
                                       Authentication authentication, CatapultOAuth2User catUser) {
        OAuth2AuthorizedClient client = authorizedClientRepository.loadAuthorizedClient("bungie", authentication,
                request);
        authorizedClientRepository.removeAuthorizedClient("bungie", authentication, request, response);
        BungieLinkService links = bungieLinks == null ? null : bungieLinks.getIfAvailable();
        Object pending = request.getAttribute(BungieLinkService.PENDING_ATTRIBUTE);
        String refreshToken = client == null || client.getRefreshToken() == null ? null
                : client.getRefreshToken().getTokenValue();
        if (links == null || !(pending instanceof BungieLinkService.PendingLink link) || refreshToken == null) {
            log.warn("Bungie link of user {} not written: {}", catUser.getUserAccount().getId(),
                    refreshToken == null ? "no refresh token" : "no checked login");
            return false;
        }
        try {
            links.complete(catUser.getUserAccount(), link, refreshToken);
            return true;
        } catch (RuntimeException e) {
            log.warn("Bungie link of user {} not written: {}", catUser.getUserAccount().getId(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private String channelIntegrationsUrl(CatapultOAuth2User catUser) {
        return UriComponentsBuilder.fromUriString(webUrl)
                .path("/channel/{username}")
                .fragment("integrations")
                .buildAndExpand(catUser.getUserAccount().getTwitchUsername())
                .toUriString();
    }

    /**
     * Spring's OAuth2UserService only exposes the access token; the refresh token
     * is only available via OAuth2AuthorizedClientRepository once the client is saved,
     * which happens before this handler is called.
     */
    private void saveRefreshToken(HttpServletRequest request, Authentication authentication, CatapultOAuth2User catUser,
                                   String registrationId, OAuthToken.Provider provider) {
        OAuth2AuthorizedClient client = authorizedClientRepository
            .loadAuthorizedClient(registrationId, authentication, request);

        if (client != null && client.getRefreshToken() != null) {
            oAuthTokenRepository.findByUserAndProvider(catUser.getUserAccount(), provider)
                .ifPresent(token -> {
                    token.setRefreshToken(tokenEncryptionService.encrypt(
                        client.getRefreshToken().getTokenValue()));
                    if (client.getAccessToken().getExpiresAt() != null) {
                        token.setExpiresAt(client.getAccessToken().getExpiresAt());
                    }
                    token.setGrantedScopes(String.join(" ", client.getAccessToken().getScopes()));
                    oAuthTokenRepository.save(token);
                    log.debug("Saved {} refresh token for user {}", provider, catUser.getUserAccount().getId());
                });
        } else {
            log.warn("No refresh token in OAuth2AuthorizedClient for user {} — {} may not have issued one",
                catUser.getUserAccount().getId(), provider);
        }
    }
}
