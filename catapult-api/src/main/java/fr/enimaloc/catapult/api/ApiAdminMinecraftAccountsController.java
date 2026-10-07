package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.AccountDto;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import fr.enimaloc.catapult.service.minecraft.MinecraftTokenService;
import fr.enimaloc.catapult.service.minecraft.MsaAuthClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/minecraft-accounts")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class ApiAdminMinecraftAccountsController {

    private final MsaAuthClient msaAuthClient;
    private final MinecraftTokenService tokenService;
    private final MinecraftService minecraftService;
    private final MinecraftServiceAccountRepository accountRepository;
    private final MinecraftFriendLinkRepository linkRepository;
    private final TokenEncryptionService encryption;

    @GetMapping
    public List<AccountDto> list() {
        return accountRepository.findAll().stream().map(this::toDto).toList();
    }

    @PostMapping("/device-code")
    public ResponseEntity<?> startDeviceCode() {
        try {
            return ResponseEntity.ok(msaAuthClient.startDeviceCode());
        } catch (HttpClientErrorException e) {
            // ex. AADSTS70002 (app Azure non déclarée client public) : le message doit
            // remonter jusqu'à l'UI admin au lieu de mourir en 500 dans les logs
            log.warn("Device-code Microsoft refusé: {}", e.getResponseBodyAsString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", "Device-code Microsoft refusé: " + e.getResponseBodyAsString()));
        }
    }

    /** 202 tant que l'admin n'a pas validé le code sur microsoft.com/link ; 200 + compte créé ensuite. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String deviceCode = requireDeviceCode(body);
        String label = body.getOrDefault("label", "Compte Minecraft");
        return msaAuthClient.pollDeviceCode(deviceCode)
                .<ResponseEntity<?>>map(tokens -> ResponseEntity.ok(finalizeAccount(tokens, label)))
                .orElseGet(ApiAdminMinecraftAccountsController::pending);
    }

    private AccountDto finalizeAccount(MsaAuthClient.MsaTokens tokens, String label) {
        MinecraftServiceAccount account = new MinecraftServiceAccount();
        account.setLabel(label);
        account.setFillOrder((int) accountRepository.count());
        account.setMinecraftUsername("(en attente)");

        ValidatedProfile profile = validateAndFetchProfile(encryption.encrypt(tokens.refreshToken()));
        account.setMsaRefreshToken(profile.rotatedRefreshTokenEncrypted());
        account.setMinecraftUsername(profile.minecraftUsername());
        account.setUpdatedAt(Instant.now());
        MinecraftServiceAccount saved = accountRepository.save(account);
        log.info("Compte de service Minecraft enrôlé: {} ({})", saved.getLabel(), profile.minecraftUsername());
        return toDto(saved, 0);
    }

    /**
     * Point d'entrée pour un compte existant dont le refresh token MSA a expiré
     * ({@code invalid_grant}) : redéroule le device-code flow et remplace le
     * refresh token sans toucher à l'id ni aux liens ({@link #delete} est bloqué
     * tant qu'un compte a des liens, donc c'est la seule voie de réparation).
     */
    @PostMapping("/{id}/reauth")
    public ResponseEntity<?> reauth(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        MinecraftServiceAccount account = findOrThrow(id);
        String deviceCode = requireDeviceCode(body);
        return msaAuthClient.pollDeviceCode(deviceCode)
                .<ResponseEntity<?>>map(tokens -> ResponseEntity.ok(reauthenticateAccount(account, tokens)))
                .orElseGet(ApiAdminMinecraftAccountsController::pending);
    }

    private AccountDto reauthenticateAccount(MinecraftServiceAccount account, MsaAuthClient.MsaTokens tokens) {
        ValidatedProfile profile = validateAndFetchProfile(encryption.encrypt(tokens.refreshToken()));
        account.setMsaRefreshToken(profile.rotatedRefreshTokenEncrypted());
        account.setMinecraftUsername(profile.minecraftUsername());
        account.setEnabled(true);
        account.setUpdatedAt(Instant.now());
        // purge le cache/état d'échec en mémoire : le prochain getToken() doit repartir
        // sur la nouvelle chaîne plutôt que de retomber sur l'ancien état "en échec"
        tokenService.evict(account.getId());
        MinecraftServiceAccount saved = accountRepository.save(account);
        log.info("Compte de service Minecraft ré-authentifié: {} ({})", saved.getLabel(), profile.minecraftUsername());
        return toDto(saved);
    }

    private record ValidatedProfile(String rotatedRefreshTokenEncrypted, String minecraftUsername) {}

    /**
     * Déroule la chaîne MSA → Xbox → Minecraft pour un refresh token chiffré et
     * récupère le pseudo Minecraft associé. Lève 502 si la chaîne échoue ou si le
     * profil Xbox/Minecraft est introuvable.
     */
    private ValidatedProfile validateAndFetchProfile(String encryptedRefreshToken) {
        String username = null;
        String rotated = null;
        try {
            var chain = tokenService.validateChain(encryptedRefreshToken);
            if (chain.isPresent()) {
                rotated = chain.get().rotatedRefreshTokenEncrypted();
                username = minecraftService.getMinecraftProfileName(chain.get().minecraftToken());
            }
        } catch (RestClientException e) {
            log.warn("Validation du compte de service Minecraft en échec: {}", e.getMessage());
        }
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Chaîne d'auth Xbox/Minecraft en échec pour ce compte (profil Xbox ou Minecraft manquant ?)");
        }
        return new ValidatedProfile(rotated, username);
    }

    @PatchMapping("/{id}")
    public AccountDto update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        MinecraftServiceAccount account = findOrThrow(id);
        if (body.containsKey("enabled")) {
            account.setEnabled(field(body, "enabled", Boolean.class));
        }
        if (body.containsKey("friendLimitReached")) {
            account.setFriendLimitReached(field(body, "friendLimitReached", Boolean.class));
        }
        if (body.containsKey("fillOrder")) {
            account.setFillOrder(field(body, "fillOrder", Number.class).intValue());
        }
        account.setUpdatedAt(Instant.now());
        return toDto(accountRepository.save(account));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        MinecraftServiceAccount account = findOrThrow(id);
        if (!linkRepository.findByServiceAccount(account).isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Des utilisateurs sont liés à ce compte"));
        }
        tokenService.evict(account.getId());
        accountRepository.delete(account);
        return ResponseEntity.noContent().build();
    }

    private MinecraftServiceAccount findOrThrow(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte inconnu"));
    }

    private static String requireDeviceCode(Map<String, String> body) {
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceCode requis");
        }
        return deviceCode;
    }

    /** 202: the admin hasn't confirmed the device code on microsoft.com/link yet. */
    private static ResponseEntity<?> pending() {
        return ResponseEntity.accepted().body(Map.of("status", "PENDING"));
    }

    /** A PATCH field of the expected JSON type, 400 otherwise. */
    private static <T> T field(Map<String, Object> body, String name, Class<T> type) {
        Object value = body.get(name);
        if (!type.isInstance(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type invalide pour " + name);
        }
        return type.cast(value);
    }

    private AccountDto toDto(MinecraftServiceAccount account) {
        return toDto(account, (int) linkRepository.countByServiceAccount(account));
    }

    private static AccountDto toDto(MinecraftServiceAccount account, int linkCount) {
        return new AccountDto(account.getId(), account.getLabel(), account.getMinecraftUsername(),
                account.getFillOrder(), account.isFriendLimitReached(), account.isEnabled(), linkCount);
    }
}
