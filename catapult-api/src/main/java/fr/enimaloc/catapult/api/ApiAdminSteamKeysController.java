package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.SteamAddKeyRequest;
import fr.enimaloc.catapult.common.dto.SteamDeleteKeyRequest;
import fr.enimaloc.catapult.common.dto.SteamKeyStatus;
import fr.enimaloc.catapult.common.dto.SteamKeysPageData;
import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin/steam-keys")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
public class ApiAdminSteamKeysController {

    private final SteamApiKeyRepository repository;
    private final SteamApiKeyRotator rotator;
    private final AdminEventPublisher events;

    /** The rotator and the admin event stream only exist when Steam (resp. SSE notifications) are enabled. */
    public ApiAdminSteamKeysController(SteamApiKeyRepository repository,
                                       @Autowired(required = false) SteamApiKeyRotator rotator,
                                       @Autowired(required = false) AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.events = events;
    }

    /** A shared key's id, masked value, owner and remaining rate-limit block. */
    private static SteamKeyStatus status(SteamApiKeyEntry entry, Map<String, Long> blockedUntil, long now) {
        String key = entry.getApiKey();
        String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
        long until = blockedUntil.getOrDefault(key, 0L);
        boolean blocked = until > now;
        long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
        return new SteamKeyStatus(ApiKeyHasher.id(key), mask(key), owner, blocked, remainingSec);
    }

    private void refreshRotator() {
        if (rotator != null) rotator.refreshKeys();
    }

    private static String mask(String key) {
        return key.length() > 8
                ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                : "…";
    }

    @GetMapping
    public SteamKeysPageData page() {
        List<SteamApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator != null ? rotator.getKeyBlockedUntil() : Map.of();
        long now = System.currentTimeMillis();

        List<SteamKeyStatus> keys = entries.stream().map(entry -> status(entry, blockedUntil, now)).toList();
        return new SteamKeysPageData(keys, rotator != null);
    }

    @PostMapping("/add")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody SteamAddKeyRequest body) {
        String trimmed = body.apiKey().trim();
        if (!trimmed.matches("[0-9A-Fa-f]{32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid API key format");
        }
        if (!repository.existsById(trimmed)) {
            repository.save(new SteamApiKeyEntry(trimmed));
            refreshRotator();
            if (events != null) {
                events.keyAdded(AdminEventPublisher.PROVIDER_STEAM,
                        new SteamKeyStatus(ApiKeyHasher.id(trimmed), mask(trimmed), null, false, 0L));
            }
        }
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestBody SteamDeleteKeyRequest body) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(SteamApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(body.keyId()))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        refreshRotator();
        if (events != null) events.keyDeleted(AdminEventPublisher.PROVIDER_STEAM, body.keyId());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        refreshRotator();
        if (events != null) events.keysRefreshed(AdminEventPublisher.PROVIDER_STEAM, page().keys());
    }

}
