package fr.enimaloc.catapult.service.provider;

import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import fr.enimaloc.catapult.service.minecraft.MinecraftTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/** The admin probe of the Minecraft services: one raw call, answered as the provider sent it. */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftRawQueries {

    private final MinecraftTokenService tokenService;
    private final MinecraftServiceAccountRepository accountRepository;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    public RawProviderResponseSupport.RawProviderResponse profileLookup(String name) {
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

    public RawProviderResponseSupport.RawProviderResponse friends(UUID accountId) {
        String token = resolveAccountToken(accountId);
        return rawSupport.fetch(() -> restClient.get()
                .uri(URI.create(MinecraftService.MINECRAFT_SERVICE_URL + "/friends"))
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    public RawProviderResponseSupport.RawProviderResponse profile(UUID accountId) {
        String token = resolveAccountToken(accountId);
        return rawSupport.fetch(() -> restClient.get()
                .uri(URI.create(MinecraftService.MINECRAFT_SERVICE_URL + "/minecraft/profile"))
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    /** The account is resolved first, so an unknown one answers 404 before an invalid status answers 400. */
    public RawProviderResponseSupport.RawProviderResponse presence(UUID accountId, String status, String activityId) {
        String token = resolveAccountToken(accountId);
        MinecraftService.PresenceStatus presenceStatus;
        try {
            presenceStatus = MinecraftService.PresenceStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid presence status: " + status);
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown Minecraft service account"));
        return tokenService.getToken(account)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Minecraft token for this account"));
    }
}
