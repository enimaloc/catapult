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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/minecraft-accounts")
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
    public MsaAuthClient.DeviceCodeStart startDeviceCode() {
        return msaAuthClient.startDeviceCode();
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
        account.setMsaRefreshToken(encryption.encrypt(tokens.refreshToken()));
        account.setFillOrder((int) accountRepository.count());
        account.setMinecraftUsername("(en attente)");

        // Déroule la chaîne une fois : valide le compte et récupère son pseudo.
        String username;
        try {
            username = tokenService.getToken(account)
                    .map(minecraftService::getMinecraftProfileName)
                    .orElse(null);
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("Validation du compte de service Minecraft en échec: {}", e.getMessage());
            username = null;
        }
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Chaîne d'auth Xbox/Minecraft en échec pour ce compte (profil Xbox ou Minecraft manquant ?)");
        }
        account.setMinecraftUsername(username);
        account.setUpdatedAt(Instant.now());
        MinecraftServiceAccount saved = accountRepository.save(account);
        log.info("Compte de service Minecraft enrôlé: {} ({})", saved.getLabel(), username);
        return new AccountDto(saved.getId(), saved.getLabel(), username, saved.getFillOrder(),
                saved.isFriendLimitReached(), saved.isEnabled(), 0);
    }

    @PatchMapping("/{id}")
    public AccountDto update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        MinecraftServiceAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte inconnu"));
        if (body.containsKey("enabled")) account.setEnabled((Boolean) body.get("enabled"));
        if (body.containsKey("friendLimitReached")) account.setFriendLimitReached((Boolean) body.get("friendLimitReached"));
        if (body.containsKey("fillOrder")) account.setFillOrder(((Number) body.get("fillOrder")).intValue());
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
