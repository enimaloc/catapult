package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.MinecraftService;
import fr.enimaloc.catapult.service.MinecraftTokenService;
import fr.enimaloc.catapult.service.MsaAuthClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
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

    public record AccountDto(UUID id, String label, String minecraftUsername, int fillOrder,
                             boolean friendLimitReached, boolean enabled, int linkCount) {}

    @GetMapping
    public List<AccountDto> list() {
        return accountRepository.findAll().stream()
                .map(a -> new AccountDto(a.getId(), a.getLabel(), a.getMinecraftUsername(),
                        a.getFillOrder(), a.isFriendLimitReached(), a.isEnabled(),
                        (int) linkRepository.countByServiceAccount(a)))
                .toList();
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
        String deviceCode = body.get("deviceCode");
        String label = body.getOrDefault("label", "Compte Minecraft");
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceCode requis");
        }

        return msaAuthClient.pollDeviceCode(deviceCode)
                .<ResponseEntity<?>>map(tokens -> ResponseEntity.ok(finalizeAccount(tokens, label)))
                .orElseGet(() -> ResponseEntity.accepted().body(Map.of("status", "PENDING")));
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
        return new AccountDto(saved.getId(), saved.getLabel(), profile.minecraftUsername(), saved.getFillOrder(),
                saved.isFriendLimitReached(), saved.isEnabled(), 0);
    }

    /**
     * Point d'entrée pour un compte existant dont le refresh token MSA a expiré
     * ({@code invalid_grant}) : redéroule le device-code flow et remplace le
     * refresh token sans toucher à l'id ni aux liens ({@link #delete} est bloqué
     * tant qu'un compte a des liens, donc c'est la seule voie de réparation).
     */
    @PostMapping("/{id}/reauth")
    public ResponseEntity<?> reauth(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        MinecraftServiceAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte inconnu"));
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceCode requis");
        }

        return msaAuthClient.pollDeviceCode(deviceCode)
                .<ResponseEntity<?>>map(tokens -> ResponseEntity.ok(reauthenticateAccount(account, tokens)))
                .orElseGet(() -> ResponseEntity.accepted().body(Map.of("status", "PENDING")));
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
        return new AccountDto(saved.getId(), saved.getLabel(), profile.minecraftUsername(), saved.getFillOrder(),
                saved.isFriendLimitReached(), saved.isEnabled(),
                (int) linkRepository.countByServiceAccount(saved));
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
        } catch (org.springframework.web.client.RestClientException e) {
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
        MinecraftServiceAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte inconnu"));
        if (body.containsKey("enabled")) {
            if (!(body.get("enabled") instanceof Boolean))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type invalide pour enabled");
            account.setEnabled((Boolean) body.get("enabled"));
        }
        if (body.containsKey("friendLimitReached")) {
            if (!(body.get("friendLimitReached") instanceof Boolean))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type invalide pour friendLimitReached");
            account.setFriendLimitReached((Boolean) body.get("friendLimitReached"));
        }
        if (body.containsKey("fillOrder")) {
            if (!(body.get("fillOrder") instanceof Number))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type invalide pour fillOrder");
            account.setFillOrder(((Number) body.get("fillOrder")).intValue());
        }
        account.setUpdatedAt(Instant.now());
        MinecraftServiceAccount saved = accountRepository.save(account);
        return new AccountDto(saved.getId(), saved.getLabel(), saved.getMinecraftUsername(),
                saved.getFillOrder(), saved.isFriendLimitReached(), saved.isEnabled(),
                (int) linkRepository.countByServiceAccount(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        MinecraftServiceAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte inconnu"));
        if (!linkRepository.findByServiceAccount(account).isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Des utilisateurs sont liés à ce compte"));
        }
        tokenService.evict(account.getId());
        accountRepository.delete(account);
        return ResponseEntity.noContent().build();
    }
}
