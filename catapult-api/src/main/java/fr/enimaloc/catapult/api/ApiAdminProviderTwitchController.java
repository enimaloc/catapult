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
        return rawSupport.fetch(() -> fetchGames(name));
    }

    @GetMapping("/users")
    public RawProviderResponseSupport.RawProviderResponse users(@RequestParam UUID userId) {
        UserAccount user = findUser(userId);
        OAuthToken token = findToken(user);
        return rawSupport.fetch(() -> fetchUsers(token, user));
    }

    @GetMapping("/moderated-channels")
    public RawProviderResponseSupport.RawProviderResponse moderatedChannels(@RequestParam UUID userId) {
        UserAccount user = findUser(userId);
        OAuthToken token = findToken(user);
        return rawSupport.fetch(() -> fetchModeratedChannels(token, user));
    }

    private String fetchGames(String name) {
        String token = twitchTokenService.getAppAccessToken();
        return restClient.get()
                .uri(TWITCH_API_URL + "/games?name=" + name)
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class);
    }

    private String fetchUsers(OAuthToken token, UserAccount user) {
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        return restClient.get()
                .uri(TWITCH_API_URL + "/users")
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class);
    }

    private String fetchModeratedChannels(OAuthToken token, UserAccount user) {
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        return restClient.get()
                .uri(TWITCH_API_URL + "/moderation/channels?user_id=" + user.getTwitchId())
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class);
    }

    private OAuthToken findToken(UserAccount user) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun token Twitch lié pour cet utilisateur"));
    }

    private UserAccount findUser(UUID userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur inconnu"));
    }
}
