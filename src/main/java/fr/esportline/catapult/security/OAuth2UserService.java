package fr.esportline.catapult.security;

import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.domain.UserSettings;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OAuth2UserService implements org.springframework.security.oauth2.client.userinfo.OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final TokenEncryptionService tokenEncryptionService;

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = delegate.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        if ("twitch".equals(registrationId)) {
            return handleTwitchLogin(userRequest, oAuth2User);
        }

        // Steam et Discord sont gérés depuis les settings, pas comme login principal
        return oAuth2User;
    }

    private OAuth2User handleTwitchLogin(OAuth2UserRequest userRequest, OAuth2User oAuth2User) {
        String twitchId = oAuth2User.getAttribute("id");
        String twitchUsername = oAuth2User.getAttribute("login");

        UserAccount account = userAccountRepository.findByTwitchId(twitchId)
            .orElseGet(() -> createNewAccount(twitchId, twitchUsername));

        // Mise à jour du username si changé
        if (!account.getTwitchUsername().equals(twitchUsername)) {
            account.setTwitchUsername(twitchUsername);
        }

        // Si compte PENDING_DELETION → annulation de la suppression
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

        String rawAccessToken = userRequest.getAccessToken().getTokenValue();
        token.setAccessToken(tokenEncryptionService.encrypt(rawAccessToken));

        if (userRequest.getAccessToken().getExpiresAt() != null) {
            token.setExpiresAt(userRequest.getAccessToken().getExpiresAt());
        }

        oAuthTokenRepository.save(token);
    }
}
