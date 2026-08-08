package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.MinecraftService;
import fr.enimaloc.catapult.service.MinecraftTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/providers/minecraft")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class ApiAdminProviderMinecraftController {

    private final MinecraftTokenService tokenService;
    private final MinecraftServiceAccountRepository accountRepository;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    @GetMapping("/profile-lookup")
    public RawProviderResponseSupport.RawProviderResponse profileLookup(@RequestParam String name) {
        return rawSupport.fetch(() -> restClient.get()
                .uri(UriComponentsBuilder
                        .fromUriString(MinecraftService.MINECRAFT_SERVICE_URL)
                        .path("/minecraft/profile/lookup/name/{name}")
                        .buildAndExpand(name)
                        .toUri())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    @GetMapping("/friends")
    public RawProviderResponseSupport.RawProviderResponse friends(@RequestParam UUID accountId) {
        String token = resolveAccountToken(accountId);
        return rawSupport.fetch(() -> restClient.get()
                .uri(URI.create(MinecraftService.MINECRAFT_SERVICE_URL + "/friends"))
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    @GetMapping("/profile")
    public RawProviderResponseSupport.RawProviderResponse profile(@RequestParam UUID accountId) {
        String token = resolveAccountToken(accountId);
        return rawSupport.fetch(() -> restClient.get()
                .uri(URI.create(MinecraftService.MINECRAFT_SERVICE_URL + "/minecraft/profile"))
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    @GetMapping("/presence")
    public RawProviderResponseSupport.RawProviderResponse presence(
            @RequestParam UUID accountId,
            @RequestParam(defaultValue = "ONLINE") String status,
            @RequestParam(required = false) String activityId) {
        String token = resolveAccountToken(accountId);
        MinecraftService.PresenceStatus presenceStatus;
        try {
            presenceStatus = MinecraftService.PresenceStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut de présence invalide: " + status);
        }
        var body = new MinecraftService.PresenceUpdate(
                presenceStatus, new MinecraftService.PresenceUpdate.JoinInfo(activityId, new String[0]));
        return rawSupport.fetch(() -> restClient.post()
                .uri(URI.create(MinecraftService.MINECRAFT_SERVICE_URL + "/presence"))
                .body(body)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    private String resolveAccountToken(UUID accountId) {
        MinecraftServiceAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte de service Minecraft inconnu"));
        return tokenService.getToken(account)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token Minecraft indisponible pour ce compte"));
    }
}
