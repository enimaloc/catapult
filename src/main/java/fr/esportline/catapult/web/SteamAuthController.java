package fr.esportline.catapult.web;

import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.event.SteamLinkedEvent;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.security.CatapultOAuth2User;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/connect/steam")
@RequiredArgsConstructor
public class SteamAuthController {

    private static final String STEAM_OPENID_ENDPOINT = "https://steamcommunity.com/openid/login";
    private static final String STEAM_ID_PREFIX = "https://steamcommunity.com/openid/id/";
    private static final String OPENID_NS = "http://specs.openid.net/auth/2.0";
    private static final String OPENID_IDENTIFIER_SELECT = "http://specs.openid.net/auth/2.0/identifier_select";

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private final UserAccountRepository userAccountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;

    @GetMapping
    public String redirectToSteam(@AuthenticationPrincipal CatapultOAuth2User principal) {
        String returnTo = baseUrl + "/connect/steam/callback";

        String redirectUrl = UriComponentsBuilder.fromUriString(STEAM_OPENID_ENDPOINT)
            .queryParam("openid.ns", OPENID_NS)
            .queryParam("openid.mode", "checkid_setup")
            .queryParam("openid.return_to", returnTo)
            .queryParam("openid.realm", baseUrl)
            .queryParam("openid.identity", OPENID_IDENTIFIER_SELECT)
            .queryParam("openid.claimed_id", OPENID_IDENTIFIER_SELECT)
            .build().toUriString();

        return "redirect:" + redirectUrl;
    }

    @GetMapping("/callback")
    public String handleCallback(@AuthenticationPrincipal CatapultOAuth2User principal,
                                 @RequestParam Map<String, String> params) {
        if (!"id_res".equals(params.get("openid.mode"))) {
            log.warn("Steam OpenID callback rejected for user {}: mode={}",
                principal.getUserAccount().getId(), params.get("openid.mode"));
            return "redirect:/settings?error=steam";
        }

        if (!verifyWithSteam(params)) {
            log.warn("Steam OpenID verification failed for user {}", principal.getUserAccount().getId());
            return "redirect:/settings?error=steam";
        }

        String claimedId = params.get("openid.claimed_id");
        if (claimedId == null || !claimedId.startsWith(STEAM_ID_PREFIX)) {
            log.warn("Invalid Steam claimed_id: {}", claimedId);
            return "redirect:/settings?error=steam";
        }

        String steamId = claimedId.substring(STEAM_ID_PREFIX.length());
        UserAccount account = principal.getUserAccount();
        account.setSteamId(steamId);
        userAccountRepository.save(account);
        log.info("Steam linked for user {}, steamId={}", account.getId(), steamId);

        eventPublisher.publishEvent(new SteamLinkedEvent(this, account));

        return "redirect:/settings";
    }

    @PostMapping("/disconnect")
    public String disconnect(@AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount account = principal.getUserAccount();
        account.setSteamId(null);
        userAccountRepository.save(account);
        log.info("Steam disconnected for user {}", account.getId());
        return "redirect:/settings";
    }

    /**
     * Vérifie la réponse OpenID auprès de Steam en mode check_authentication.
     * Steam répond avec "is_valid:true" si la signature est valide.
     */
    private boolean verifyWithSteam(Map<String, String> params) {
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            params.forEach((k, v) -> body.put(k, List.of(v)));
            body.set("openid.mode", "check_authentication");

            String response = restClient.post()
                .uri(STEAM_OPENID_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(String.class);

            return response != null && response.contains("is_valid:true");
        } catch (Exception e) {
            log.error("Steam OpenID verification call failed: {}", e.getMessage());
            return false;
        }
    }
}
