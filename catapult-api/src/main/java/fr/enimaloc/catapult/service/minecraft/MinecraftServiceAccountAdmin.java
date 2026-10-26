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
            // e.g. AADSTS70002 (Azure app not declared as a public client): the message must
            // reach the admin UI instead of dying as a 500 in the logs
            log.warn("Microsoft refused the device code: {}", e.getResponseBodyAsString());
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
        account.setMinecraftUsername("(pending)");

        ValidatedProfile profile = validateAndFetchProfile(encryption.encrypt(tokens.refreshToken()));
        account.setMsaRefreshToken(profile.rotatedRefreshTokenEncrypted());
        account.setMinecraftUsername(profile.minecraftUsername());
        account.setUpdatedAt(Instant.now());
        MinecraftServiceAccount saved = accountRepository.save(account);
        log.info("Minecraft service account enrolled: {} ({})", saved.getLabel(), profile.minecraftUsername());
        return toDto(saved, 0);
    }

    /**
     * Entry point for an existing account whose MSA refresh token has expired
     * ({@code invalid_grant}): runs the device-code flow again and replaces the
     * refresh token without touching the id or the links ({@link #delete} is blocked
     * while an account has links, so this is the only way to repair it).
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
        // purge the in-memory cache/failure state: the next getToken() must start over
        // on the new chain rather than fall back on the old "failing" state
        tokenService.evict(account.getId());
        MinecraftServiceAccount saved = accountRepository.save(account);
        log.info("Minecraft service account re-authenticated: {} ({})", saved.getLabel(), profile.minecraftUsername());
        return toDto(saved);
    }

    private record ValidatedProfile(String rotatedRefreshTokenEncrypted, String minecraftUsername) {}

    /**
     * Walks the MSA → Xbox → Minecraft chain for an encrypted refresh token and
     * fetches the associated Minecraft player name. Throws 502 if the chain fails or
     * the Xbox/Minecraft profile is missing.
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
            log.warn("Minecraft service account validation failed: {}", e.getMessage());
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
