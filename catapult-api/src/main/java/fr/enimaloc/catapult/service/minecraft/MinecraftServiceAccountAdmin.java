package fr.enimaloc.catapult.service.minecraft;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.admin.AccountDto;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** An admin's pool of Minecraft service accounts: device-code enrolment, re-auth, edits and removal. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftServiceAccountAdmin {

    private final MsaAuthClient msaAuthClient;
    private final MinecraftTokenService tokenService;
    private final MinecraftService minecraftService;
    private final MinecraftServiceAccountRepository accountRepository;
    private final MinecraftFriendLinkRepository linkRepository;
    private final TokenEncryptionService encryption;

    public List<AccountDto> list() {
        return accountRepository.findAll().stream().map(this::toDto).toList();
    }

    public MsaAuthClient.DeviceCodeStart startDeviceCode() {
        try {
            return msaAuthClient.startDeviceCode();
        } catch (HttpClientErrorException e) {
            // ex. AADSTS70002 (app Azure non déclarée client public) : le message doit
            // remonter jusqu'à l'UI admin au lieu de mourir en 500 dans les logs
            log.warn("Device-code Microsoft refusé: {}", e.getResponseBodyAsString());
            throw new ApiProblem(ErrorCode.MINECRAFT_DEVICE_CODE_REFUSED, "Microsoft refused the device code",
                    Map.of("upstream", e.getResponseBodyAsString()));
        }
    }

    /** Empty while the admin hasn't validated the code on microsoft.com/link; the created account afterwards. */
    public Optional<AccountDto> create(String deviceCode, String label) {
        requireDeviceCode(deviceCode);
        return msaAuthClient.pollDeviceCode(deviceCode).map(tokens -> finalizeAccount(tokens, label));
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
     * Empty while the admin hasn't validated the code yet.
     */
    public Optional<AccountDto> reauth(UUID id, String deviceCode) {
        MinecraftServiceAccount account = findOrThrow(id);
        requireDeviceCode(deviceCode);
        return msaAuthClient.pollDeviceCode(deviceCode).map(tokens -> reauthenticateAccount(account, tokens));
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
                    "The Xbox/Minecraft auth chain failed for this account (missing Xbox or Minecraft profile?)");
        }
        return new ValidatedProfile(rotated, username);
    }

    /** 404 for an unknown account before 400 for a field of the wrong type. */
    public AccountDto update(UUID id, Map<String, Object> body) {
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

    public void delete(UUID id) {
        MinecraftServiceAccount account = findOrThrow(id);
        if (!linkRepository.findByServiceAccount(account).isEmpty()) {
            throw new ApiProblem(ErrorCode.MINECRAFT_ACCOUNT_IN_USE, "Members are linked to this account");
        }
        tokenService.evict(account.getId());
        accountRepository.delete(account);
    }

    private MinecraftServiceAccount findOrThrow(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown account"));
    }

    private static void requireDeviceCode(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceCode is required");
        }
    }

    /** A PATCH field of the expected JSON type, 400 otherwise. */
    private static <T> T field(Map<String, Object> body, String name, Class<T> type) {
        Object value = body.get(name);
        if (!type.isInstance(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type for " + name);
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
