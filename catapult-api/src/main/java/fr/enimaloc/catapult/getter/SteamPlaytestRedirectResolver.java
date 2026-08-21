package fr.enimaloc.catapult.getter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steam serves playtest store pages as an HTTP 302 straight to the base
 * game's page ({@code /app/{playtestId}} -> {@code Location: /app/{baseId}}).
 * Regular game/demo pages return 200 directly. The shared {@link RestClient}
 * is backed by a JDK {@link java.net.http.HttpClient} whose default redirect
 * policy is NEVER, so the 302 reaches us instead of being followed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SteamPlaytestRedirectResolver {

    private static final Pattern APP_ID_PATTERN = Pattern.compile("/app/(\\d+)");

    private final RestClient restClient;

    public Optional<String> resolveParentAppId(String appId) {
        try {
            ResponseEntity<Void> response = restClient.get()
                .uri("https://store.steampowered.com/app/{appId}", appId)
                .retrieve()
                .toBodilessEntity();

            if (!response.getStatusCode().is3xxRedirection()) return Optional.empty();

            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location == null) return Optional.empty();

            Matcher matcher = APP_ID_PATTERN.matcher(location);
            if (!matcher.find()) return Optional.empty();

            String parentId = matcher.group(1);
            return parentId.equals(appId) ? Optional.empty() : Optional.of(parentId);
        } catch (Exception e) {
            log.warn("Steam playtest redirect check failed for appId={}: {}", appId, e.getMessage());
            return Optional.empty();
        }
    }
}
