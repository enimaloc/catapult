package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.TwitchTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/providers/twitch")
@RequiredArgsConstructor
public class ApiAdminProviderTwitchController {

    private static final String TWITCH_API_URL = "https://api.twitch.tv/helix";

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final TwitchTokenService twitchTokenService;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    @GetMapping("/games")
    public RawProviderResponseSupport.RawProviderResponse games(@RequestParam String name) {
        String token = twitchTokenService.getAppAccessToken();
        return rawSupport.fetch(() -> restClient.get()
                .uri(TWITCH_API_URL + "/games?name=" + name)
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class));
    }

    @GetMapping("/users")
    public RawProviderResponseSupport.RawProviderResponse users(@RequestParam UUID userId) {
        String token = resolveUserToken(userId);
        return rawSupport.fetch(() -> restClient.get()
                .uri(TWITCH_API_URL + "/users")
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class));
    }

    @GetMapping("/moderated-channels")
    public RawProviderResponseSupport.RawProviderResponse moderatedChannels(@RequestParam UUID userId) {
        UserAccount user = findUser(userId);
        String token = resolveUserToken(user);
        return rawSupport.fetch(() -> restClient.get()
                .uri(TWITCH_API_URL + "/moderation/channels?user_id=" + user.getTwitchId())
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class));
    }

    private String resolveUserToken(UUID userId) {
        return resolveUserToken(findUser(userId));
    }

    private String resolveUserToken(UserAccount user) {
        OAuthToken token = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun token Twitch lié pour cet utilisateur"));
        return twitchTokenService.resolveAccessToken(token, user);
    }

    private UserAccount findUser(UUID userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur inconnu"));
    }
}
