package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin/steam-keys")
public class ApiAdminSteamKeysController {

    private final SteamApiKeyRepository repository;
    private final SteamApiKeyRotator rotator;

    public ApiAdminSteamKeysController(SteamApiKeyRepository repository) {
        this(repository, null);
    }

    @Autowired
    public ApiAdminSteamKeysController(SteamApiKeyRepository repository,
                                       @Autowired(required = false) SteamApiKeyRotator rotator) {
        this.repository = repository;
        this.rotator = rotator;
    }

    @GetMapping
    public SteamKeysPageData page() {
        List<SteamApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator != null ? rotator.getKeyBlockedUntil() : Map.of();
        long now = System.currentTimeMillis();

        List<KeyStatus> keys = new ArrayList<>();
        for (SteamApiKeyEntry entry : entries) {
            String key = entry.getApiKey();
            String masked = key.length() > 8
                    ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                    : "…";
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), masked, owner, blocked, remainingSec));
        }

        return new SteamKeysPageData(keys, rotator != null);
    }

    @PostMapping("/add")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddKeyRequest body) {
        String trimmed = body.apiKey().trim();
        if (!trimmed.matches("[0-9A-Fa-f]{32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid API key format");
        }
        if (!repository.existsById(trimmed)) {
            repository.save(new SteamApiKeyEntry(trimmed));
            if (rotator != null) rotator.refreshKeys();
        }
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestBody DeleteKeyRequest body) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(SteamApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(body.keyId()))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        if (rotator != null) rotator.refreshKeys();
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        if (rotator != null) rotator.refreshKeys();
    }

    public record SteamKeysPageData(List<KeyStatus> keys, boolean steamEnabled) {}

    public record KeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}

    public record AddKeyRequest(String apiKey) {}

    public record DeleteKeyRequest(String keyId) {}
}
