package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.DeleteKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.DtddKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.domain.dtdd.DtddApiKeyEntry;
import fr.enimaloc.catapult.getter.dtdd.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.dtdd.DtddApiKeyRepository;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
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
    private final Optional<DtddApiKeyRotator> rotator;
    private final AdminEventPublisher events;

    public ApiAdminDtddKeysController(DtddApiKeyRepository repository,
                                      Optional<DtddApiKeyRotator> rotator,
                                      AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.events = events;
    }

    private static String mask(String key) {
        return key.length() > 8
                ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                : "…";
    }

    @GetMapping
    public DtddKeysPageData page() {
        List<DtddApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator.map(DtddApiKeyRotator::getKeyBlockedUntil).orElseGet(Map::of);
        long now = System.currentTimeMillis();

        List<KeyStatus> keys = new ArrayList<>();
        for (DtddApiKeyEntry entry : entries) {
            String key = entry.getApiKey();
            String masked = mask(key);
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), masked, owner, blocked, remainingSec));
        }
        return new DtddKeysPageData(keys, rotator.isPresent());
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
            rotator.ifPresent(DtddApiKeyRotator::refreshKeys);
            events.keyAdded(AdminEventPublisher.PROVIDER_DTDD,
                    new KeyStatus(ApiKeyHasher.id(trimmed), mask(trimmed), null, false, 0L));
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
        rotator.ifPresent(DtddApiKeyRotator::refreshKeys);
        events.keyDeleted(AdminEventPublisher.PROVIDER_DTDD, body.keyId());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        rotator.ifPresent(DtddApiKeyRotator::refreshKeys);
        events.keysRefreshed(AdminEventPublisher.PROVIDER_DTDD, page().keys());
    }
}
