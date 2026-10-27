package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.connect.SteamStartResponse;
import fr.enimaloc.catapult.service.steam.SteamOpenIdLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ApiSteamConnectController {

    @Value("${app.web-url:http://localhost:8081}")
    private String webBaseUrl;

    private final SteamOpenIdLinkService steamLinks;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping(ApiPaths.USER + "/links/steam/start")
    public SteamStartResponse start(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UUID caller = UUID.fromString(ApiUserResolver.requireJwt(jwt).getSubject());
        return new SteamStartResponse(steamLinks.startRedirect(caller));
    }

    // This endpoint is called directly by Steam (browser redirect), not by catapult-web
    @GetMapping("/api/connect/steam/callback")
    public ResponseEntity<Void> callback(@RequestParam Map<String, String> params) {
        return closePopup(steamLinks.link(params).query());
    }

    /** Sends the browser to catapult-web's page that closes the Steam popup, with {@code query} appended. */
    private ResponseEntity<Void> closePopup(String query) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", webBaseUrl + "/connect/steam/close" + query)
                .build();
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping(ApiPaths.USER + "/links/steam")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        steamLinks.unlink(UUID.fromString(ApiUserResolver.requireJwt(jwt).getSubject()));
    }
}
