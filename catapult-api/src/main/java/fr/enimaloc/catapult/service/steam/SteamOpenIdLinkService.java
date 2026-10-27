package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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

/** The Steam OpenID round trip that links a Steam account to a member: the redirect out and Steam's callback. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SteamOpenIdLinkService {

    private static final String STEAM_OPENID_ENDPOINT = "https://steamcommunity.com/openid/login";
    private static final String STEAM_ID_PREFIX = "https://steamcommunity.com/openid/id/";
    private static final String OPENID_NS = "http://specs.openid.net/auth/2.0";
    private static final String OPENID_IDENTIFIER_SELECT = "http://specs.openid.net/auth/2.0/identifier_select";
    private static final Random RANDOM = new SecureRandom();

    /** How the callback ended, as the query catapult-web's popup-closing page receives. */
    public enum SteamLinkOutcome {
        LINKED(""),
        NONCE("?error=nonce"),
        REJECTED("?error=rejected"),
        VERIFY("?error=verify"),
        INVALID("?error=invalid");

        private final String query;

        SteamLinkOutcome(String query) {
            this.query = query;
        }

        public String query() {
            return query;
        }
    }

    // nonce → userId, expires after 5 minutes
    private final ConcurrentHashMap<String, UUID> pendingNonces = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Value("${app.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    private final UserAccountRepository userAccountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ChannelEventPublisher channelEventPublisher;
    private final RestClient restClient;
    private final DebugRecording debug;

    /** Remembers {@code caller} under a fresh nonce and returns the Steam login URL that will hand it back. */
    public String startRedirect(UUID caller) {
        String nonce = generateNonce();
        pendingNonces.put(nonce, caller);
        scheduler.schedule(() -> pendingNonces.remove(nonce), 5, TimeUnit.MINUTES);

        String returnTo = apiBaseUrl + "/api/connect/steam/callback?nonce=" + nonce;
        return UriComponentsBuilder.fromUriString(STEAM_OPENID_ENDPOINT)
                .queryParam("openid.ns", OPENID_NS)
                .queryParam("openid.mode", "checkid_setup")
                .queryParam("openid.return_to", returnTo)
                .queryParam("openid.realm", apiBaseUrl)
                .queryParam("openid.identity", OPENID_IDENTIFIER_SELECT)
                .queryParam("openid.claimed_id", OPENID_IDENTIFIER_SELECT)
                .build().toUriString();
    }

    public SteamLinkOutcome link(Map<String, String> params) {
        String nonce = params.get("nonce");
        UUID userId = nonce != null ? pendingNonces.remove(nonce) : null;

        if (userId == null) {
            log.warn("Steam OpenID callback with unknown or expired nonce: {}", nonce);
            return SteamLinkOutcome.NONCE;
        }
        // Steam's redirect carries no JWT: the nonce is what says which member this is for
        return DebugContext.callAs(userId, () -> {
            debug.inbound(userId, "steam", "GET", callbackUri(params), null, () -> "openid.callback");
            return link(userId, params);
        });
    }

    public void unlink(UUID caller) {
        UserAccount account = findOrThrow(caller);
        account.setSteamId(null);
        userAccountRepository.save(account);
        log.info("Steam disconnected for user {}", account.getId());
        channelEventPublisher.connectionChanged(account.getId(),
                new ProviderConnectionsDto("STEAM", false, null));
    }

    /** The callback URL as Steam sent the browser to it, for the members in debug mode. */
    private URI callbackUri(Map<String, String> params) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(apiBaseUrl + "/api/connect/steam/callback");
            params.forEach(uri::queryParam);
            return uri.build().encode().toUri();
        } catch (RuntimeException e) {
            log.warn("Could not rebuild the Steam callback URL for the debug recording: {}", e.toString());
            return null;
        }
    }

    private SteamLinkOutcome link(UUID userId, Map<String, String> params) {
        if (!"id_res".equals(params.get("openid.mode"))) {
            log.warn("Steam OpenID callback rejected: mode={}", params.get("openid.mode"));
            return SteamLinkOutcome.REJECTED;
        }

        if (!verifyWithSteam(params)) {
            log.warn("Steam OpenID verification failed for user {}", userId);
            return SteamLinkOutcome.VERIFY;
        }

        String claimedId = params.get("openid.claimed_id");
        if (claimedId == null || !claimedId.startsWith(STEAM_ID_PREFIX)) {
            log.warn("Invalid Steam claimed_id: {}", claimedId);
            return SteamLinkOutcome.INVALID;
        }

        String steamId = claimedId.substring(STEAM_ID_PREFIX.length());
        UserAccount account = findOrThrow(userId);
        account.setSteamId(steamId);
        userAccountRepository.save(account);
        log.info("Steam linked for user {}, steamId={}", account.getId(), steamId);
        eventPublisher.publishEvent(new SteamLinkedEvent(this, account));
        channelEventPublisher.connectionChanged(account.getId(),
                new ProviderConnectionsDto("STEAM", true, null));

        return SteamLinkOutcome.LINKED;
    }

    private UserAccount findOrThrow(UUID userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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
}
