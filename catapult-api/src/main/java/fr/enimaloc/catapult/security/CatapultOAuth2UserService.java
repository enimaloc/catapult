package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.InviteService;
import fr.enimaloc.catapult.service.WhitelistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatapultOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;
    private final WhitelistService whitelistService;
    private final AdminMigrationService adminMigrationService;
    private final InviteService inviteService;

    @Value("${app.owner-id:}")
    private String ownerId;

    @Value("${twitch.default-no-game.name:}")
    private String defaultNoGameName;

    @Value("${twitch.default-no-game.id:}")
    private String defaultNoGameId;

    @Value("${twitch.default-incomplete-game.name:}")
    private String defaultIncompleteGameName;

    @Value("${twitch.default-incomplete-game.id:}")
    private String defaultIncompleteGameId;


    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        try {
            return switch (registrationId) {
                case "twitch" -> handleTwitchLogin(userRequest, fetchTwitchUser(userRequest));
                case "steam" -> handleSecondaryLink(userRequest, OAuthToken.Provider.STEAM);
//                case "xbox" -> handleSecondaryLink(userRequest, OAuthToken.Provider.XBOX);
//                case "battlenet" -> handleSecondaryLink(userRequest, OAuthToken.Provider.BATTLENET);
                default -> delegate.loadUser(userRequest);
            };
        } catch (OAuth2AuthenticationException e) {
            if (!"not_whitelisted".equals(e.getError().getErrorCode())) {
                log.error("OAuth2 authentication failed for provider '{}': {} — {}",
                    registrationId, e.getError().getErrorCode(), e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during OAuth2 login for provider '{}'", registrationId, e);
            throw new OAuth2AuthenticationException(new OAuth2Error("server_error"), e);
        }
    }

    /**
     * Discord is a secondary provider (game detection), not a login provider.
     * When a user links Discord, they are already authenticated via Twitch.
     * We save the Discord token for their account and return their existing principal.
     */
    private OAuth2User handleSecondaryLink(OAuth2UserRequest userRequest, OAuthToken.Provider provider) {
        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        if (currentAuth == null || !(currentAuth.getPrincipal() instanceof CatapultOAuth2User existingUser)) {
            log.error("{} link attempted without an authenticated Twitch session", provider);
            throw new OAuth2AuthenticationException(new OAuth2Error("unauthorized"));
        }

        saveToken(existingUser.getUserAccount(), provider, userRequest);
        log.info("{} linked for user {}", provider, existingUser.getUserAccount().getId());
        return existingUser;
    }

    /**
     * Twitch's /helix/users endpoint requires a Client-ID header in addition to the
     * Authorization header, and wraps the user in a "data" array.
     * Spring Security's DefaultOAuth2UserService handles neither, so we call it ourselves.
     */
    @SuppressWarnings("unchecked")
    private OAuth2User fetchTwitchUser(OAuth2UserRequest userRequest) {
        String accessToken = userRequest.getAccessToken().getTokenValue();
        String clientId = userRequest.getClientRegistration().getClientId();
        String userInfoUri = userRequest.getClientRegistration()
            .getProviderDetails().getUserInfoEndpoint().getUri();

        Map<String, Object> response = restClient.get()
            .uri(userInfoUri)
            .header("Authorization", "Bearer " + accessToken)
            .header("Client-ID", clientId)
            .retrieve()
            .body(Map.class);

        if (response == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response"));
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("empty_user_info_response"));
        }

        return new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            data.getFirst(),
            "id"
        );
    }

    private OAuth2User handleTwitchLogin(OAuth2UserRequest userRequest, OAuth2User oAuth2User) {
        String twitchId = oAuth2User.getAttribute("id");

        String twitchUsername = oAuth2User.getAttribute("login");

        boolean isOwner = !ownerId.isBlank() && ownerId.equals(twitchId);
        boolean grantInviteAfterCreate = false;
        if (whitelistService.isEnabled() && !isOwner && !whitelistService.contains(twitchId)) {
            Optional<String> pendingInvite = getPendingInviteCode();
            log.info("Whitelist check — id={}, whitelistEnabled={}, inWhitelist={}, inviteCode={}",
                twitchId, whitelistService.isEnabled(), whitelistService.contains(twitchId),
                pendingInvite.orElse("(none)"));
            if (pendingInvite.isEmpty()) {
                log.warn("Access denied - user not whitelisted: id={}, login={}", twitchId, twitchUsername);
                throw new OAuth2AuthenticationException(new OAuth2Error("not_whitelisted"), "User not whitelisted.");
            }
            // Invite code valid — adds user to whitelist, then fall through to normal account creation
            grantInviteAfterCreate = inviteService.redeem(pendingInvite.get(), twitchId);
            clearPendingInviteCode();
        }

        Optional<UserAccount> existing = userAccountRepository.findByTwitchId(twitchId);
        boolean isNew = existing.isEmpty();
        UserAccount account = existing.orElseGet(() -> createNewAccount(twitchId, twitchUsername));

        if (account.isSystemAccount()) {
            throw new OAuth2AuthenticationException(
                new OAuth2Error("system_account_login_forbidden"),
                "The system account cannot be used to login.");
        }

        if (!Objects.equals(account.getTwitchUsername(), twitchUsername)) {
            account.setTwitchUsername(twitchUsername);
        }

        String profileImageUrl = oAuth2User.getAttribute("profile_image_url");
        if (profileImageUrl != null && !profileImageUrl.equals(account.getProfileImageUrl())) {
            account.setProfileImageUrl(profileImageUrl);
        }

        if (account.getStatus() == UserAccount.Status.PENDING_DELETION) {
            account.setStatus(UserAccount.Status.ACTIVE);
            account.setDeletionRequestedAt(null);
        } else if (account.getStatus() == UserAccount.Status.INACTIVE) {
            account.setStatus(UserAccount.Status.ACTIVE);
        }

        userAccountRepository.save(account);
        saveToken(account, OAuthToken.Provider.TWITCH, userRequest);

        if (isNew) {
            eventPublisher.publishEvent(new AccountCreatedEvent(this, account));
        }

        if (grantInviteAfterCreate) {
            inviteService.grantInvite(account);
        }

        return new CatapultOAuth2User(oAuth2User, account, isOwner);
    }

    private UserAccount createNewAccount(String twitchId, String twitchUsername) {
        UserAccount account = new UserAccount();
        account.setTwitchId(twitchId);
        account.setTwitchUsername(twitchUsername);
        account = userAccountRepository.save(account);

        final UserAccount saved = account;
        userAccountRepository.findBySystemAccountTrue().ifPresentOrElse(
            system -> adminMigrationService.migrate(
                system, saved, new AdminMigrationService.MigrateOptions(true, true, false)),
            () -> initDefaultSettings(saved)
        );

        return account;
    }

    private void initDefaultSettings(UserAccount account) {
        UserSettings settings = new UserSettings();
        settings.setUser(account);
        if (!Strings.isEmpty(defaultNoGameId) && !Strings.isEmpty(defaultNoGameName)) {
            settings.setNoGameTwitchGameId(defaultNoGameId);
            settings.setNoGameTwitchGameName(defaultNoGameName);
        }
        if (!Strings.isEmpty(defaultIncompleteGameId) && !Strings.isEmpty(defaultIncompleteGameName)) {
            settings.setIncompleteFallbackTwitchGameId(defaultIncompleteGameId);
            settings.setIncompleteFallbackTwitchGameName(defaultIncompleteGameName);
        }
        userSettingsRepository.save(settings);

        int priority = 1;
        for (GetterConfig.Provider provider : GetterConfig.Provider.values()) {
            GetterConfig config = new GetterConfig();
            config.setUser(account);
            config.setProvider(provider);
            config.setPriority(priority++);
            config.setEnabled(provider == GetterConfig.Provider.STEAM);
            getterConfigRepository.save(config);
        }
    }

    private void saveToken(UserAccount account, OAuthToken.Provider provider, OAuth2UserRequest userRequest) {
        OAuthToken token = oAuthTokenRepository.findByUserAndProvider(account, provider)
            .orElseGet(() -> {
                OAuthToken t = new OAuthToken();
                t.setUser(account);
                t.setProvider(provider);
                return t;
            });

        token.setAccessToken(tokenEncryptionService.encrypt(userRequest.getAccessToken().getTokenValue()));

        if (userRequest.getAccessToken().getExpiresAt() != null) {
            token.setExpiresAt(userRequest.getAccessToken().getExpiresAt());
        }

        oAuthTokenRepository.save(token);
    }

    private Optional<String> getPendingInviteCode() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            HttpSession session = request.getSession(false);
            String fromSession = session != null
                ? (String) session.getAttribute(InviteCodeRelayFilter.SESSION_KEY)
                : null;
            String fromCookie = readCookie(request, InviteCodeRelayFilter.COOKIE_NAME);
            log.info("getPendingInviteCode — sessionId={}, fromSession={}, fromCookie={}",
                session != null ? session.getId() : "null", fromSession, fromCookie);
            return Optional.ofNullable(fromSession != null ? fromSession : fromCookie);
        } catch (IllegalStateException e) {
            log.warn("getPendingInviteCode — no request context: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private void clearPendingInviteCode() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(InviteCodeRelayFilter.SESSION_KEY);
            }
            org.springframework.http.ResponseCookie expired = org.springframework.http.ResponseCookie
                .from(InviteCodeRelayFilter.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
            attrs.getResponse().addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, expired.toString());
        } catch (IllegalStateException ignored) {}
    }

    private static String readCookie(HttpServletRequest request, String name) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (jakarta.servlet.http.Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
