package fr.esportline.catapult.security;

import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.domain.UserSettings;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CatapultOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestClient restClient;

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        if ("twitch".equals(registrationId)) {
            return handleTwitchLogin(userRequest, fetchTwitchUser(userRequest));
        }

        return delegate.loadUser(userRequest);
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
            data.get(0),
            "id"
        );
    }

    private OAuth2User handleTwitchLogin(OAuth2UserRequest userRequest, OAuth2User oAuth2User) {
        String twitchId = oAuth2User.getAttribute("id");
        String twitchUsername = oAuth2User.getAttribute("login");

        UserAccount account = userAccountRepository.findByTwitchId(twitchId)
            .orElseGet(() -> createNewAccount(twitchId, twitchUsername));

        if (!account.getTwitchUsername().equals(twitchUsername)) {
            account.setTwitchUsername(twitchUsername);
        }

        if (account.getStatus() == UserAccount.Status.PENDING_DELETION) {
            account.setStatus(UserAccount.Status.ACTIVE);
            account.setDeletionRequestedAt(null);
        }

        userAccountRepository.save(account);
        saveToken(account, OAuthToken.Provider.TWITCH, userRequest);

        return new CatapultOAuth2User(oAuth2User, account);
    }

    private UserAccount createNewAccount(String twitchId, String twitchUsername) {
        UserAccount account = new UserAccount();
        account.setTwitchId(twitchId);
        account.setTwitchUsername(twitchUsername);
        account = userAccountRepository.save(account);

        UserSettings settings = new UserSettings();
        settings.setUser(account);
        userSettingsRepository.save(settings);

        return account;
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
}
