package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.DtddApiKeyEntry;
import fr.enimaloc.catapult.getter.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.DtddApiKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/dtdd-keys")
public class ApiAdminDtddKeysController {

    private static final Pattern KEY_PATTERN = Pattern.compile("ddd_[A-Za-z0-9]{16,128}");

    private final DtddApiKeyRepository repository;
    private final DtddApiKeyRotator rotator;

    public ApiAdminDtddKeysController(DtddApiKeyRepository repository) {
        this(repository, null);
    }

    @Autowired
    public ApiAdminDtddKeysController(DtddApiKeyRepository repository,
                                      @Autowired(required = false) DtddApiKeyRotator rotator) {
        this.repository = repository;
        this.rotator = rotator;
    }

    @GetMapping
    public DtddKeysPageData page() {
        List<DtddApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator != null ? rotator.getKeyBlockedUntil() : Map.of();
        long now = System.currentTimeMillis();

        List<KeyStatus> keys = new ArrayList<>();
        for (DtddApiKeyEntry entry : entries) {
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
        return new DtddKeysPageData(keys, rotator != null);
    }

    @PostMapping("/add")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddKeyRequest body) {
        String trimmed = body.apiKey().trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid DTDD API key format");
        }
        if (!repository.existsById(trimmed)) {
            repository.save(new DtddApiKeyEntry(trimmed));
            if (rotator != null) rotator.refreshKeys();
        }
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestBody DeleteKeyRequest body) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(DtddApiKeyEntry::getApiKey)
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

    public record DtddKeysPageData(List<KeyStatus> keys, boolean dtddEnabled) {}
    public record KeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}
    public record AddKeyRequest(String apiKey) {}
    public record DeleteKeyRequest(String keyId) {}
}
