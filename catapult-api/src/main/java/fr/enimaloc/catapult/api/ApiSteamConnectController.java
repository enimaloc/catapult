package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/connect/steam")
@RequiredArgsConstructor
public class ApiSteamConnectController {

    private static final String STEAM_OPENID_ENDPOINT = "https://steamcommunity.com/openid/login";
    private static final String STEAM_ID_PREFIX = "https://steamcommunity.com/openid/id/";
    private static final String OPENID_NS = "http://specs.openid.net/auth/2.0";
    private static final String OPENID_IDENTIFIER_SELECT = "http://specs.openid.net/auth/2.0/identifier_select";
    private static final Random RANDOM = new SecureRandom();

    // nonce → userId, expires after 5 minutes
    private final ConcurrentHashMap<String, UUID> pendingNonces = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Value("${app.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    @Value("${app.web-url:http://localhost:8081}")
    private String webBaseUrl;

    private final UserAccountRepository userAccountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;

    @GetMapping("/start")
    public SteamStartResponse start(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String nonce = generateNonce();
        pendingNonces.put(nonce, userId);
        scheduler.schedule(() -> pendingNonces.remove(nonce), 5, TimeUnit.MINUTES);

        String returnTo = apiBaseUrl + "/api/connect/steam/callback?nonce=" + nonce;
        String redirectUrl = UriComponentsBuilder.fromUriString(STEAM_OPENID_ENDPOINT)
                .queryParam("openid.ns", OPENID_NS)
                .queryParam("openid.mode", "checkid_setup")
                .queryParam("openid.return_to", returnTo)
                .queryParam("openid.realm", apiBaseUrl)
                .queryParam("openid.identity", OPENID_IDENTIFIER_SELECT)
                .queryParam("openid.claimed_id", OPENID_IDENTIFIER_SELECT)
                .build().toUriString();

        return new SteamStartResponse(redirectUrl);
    }

    // This endpoint is called directly by Steam (browser redirect), not by catapult-web
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam Map<String, String> params) {
        String nonce = params.get("nonce");
        UUID userId = nonce != null ? pendingNonces.remove(nonce) : null;

        if (userId == null) {
            log.warn("Steam OpenID callback with unknown or expired nonce: {}", nonce);
            return redirect(webBaseUrl + "/connect/steam/close?error=nonce");
        }

        if (!"id_res".equals(params.get("openid.mode"))) {
            log.warn("Steam OpenID callback rejected: mode={}", params.get("openid.mode"));
            return redirect(webBaseUrl + "/connect/steam/close?error=rejected");
        }

        if (!verifyWithSteam(params)) {
            log.warn("Steam OpenID verification failed for user {}", userId);
            return redirect(webBaseUrl + "/connect/steam/close?error=verify");
        }

        String claimedId = params.get("openid.claimed_id");
        if (claimedId == null || !claimedId.startsWith(STEAM_ID_PREFIX)) {
            log.warn("Invalid Steam claimed_id: {}", claimedId);
            return redirect(webBaseUrl + "/connect/steam/close?error=invalid");
        }

        String steamId = claimedId.substring(STEAM_ID_PREFIX.length());
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        account.setSteamId(steamId);
        userAccountRepository.save(account);
        log.info("Steam linked for user {}, steamId={}", account.getId(), steamId);
        eventPublisher.publishEvent(new SteamLinkedEvent(this, account));

        return redirect(webBaseUrl + "/connect/steam/close");
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", url)
                .build();
    }

    @PostMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        account.setSteamId(null);
        userAccountRepository.save(account);
        log.info("Steam disconnected for user {}", account.getId());
    }

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

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

    public record SteamStartResponse(String redirectUrl) {}
}
