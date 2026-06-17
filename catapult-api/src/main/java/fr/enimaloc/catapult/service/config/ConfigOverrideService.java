package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.domain.ConfigAudit;
import fr.enimaloc.catapult.domain.ConfigOverride;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ConfigOverrideAppliedEvent;
import fr.enimaloc.catapult.repository.ConfigAuditRepository;
import fr.enimaloc.catapult.repository.ConfigOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ConfigOverrideService {

    private final ConfigOverrideRepository overrideRepo;
    private final ConfigAuditRepository auditRepo;
    private final DatabaseOverridePropertySource source;
    private final ConfigCatalogProperties props;
    private final ContextRefresher refresher;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void apply(String key, String newValue, UserAccount actor) {
        validate(key);
        boolean secret = isSecret(key);

        Optional<ConfigOverride> existing = overrideRepo.findById(key);
        String previousValue = existing.map(ConfigOverride::getValue).orElse(null);

        ConfigOverride ov = existing.orElseGet(ConfigOverride::new);
        ov.setKey(key);
        ov.setValue(newValue);
        ov.setSecret(secret);
        ov.setUpdatedAt(Instant.now());
        ov.setUpdatedBy(actor.getId());
        overrideRepo.save(ov);

        ConfigAudit audit = new ConfigAudit();
        audit.setKey(key);
        audit.setPreviousValue(secret ? "***" : previousValue);
        audit.setNewValue(secret ? "***" : newValue);
        audit.setChangedAt(Instant.now());
        audit.setChangedBy(actor.getId());
        auditRepo.save(audit);

        source.put(key, newValue);
        refresher.refresh();
        eventPublisher.publishEvent(new ConfigOverrideAppliedEvent(this, key, newValue));
    }

    @Transactional
    public void clear(String key, UserAccount actor) {
        validate(key);
        Optional<ConfigOverride> existing = overrideRepo.findById(key);
        if (existing.isEmpty()) {
            return;
        }
        ConfigOverride ov = existing.get();
        boolean secret = ov.isSecret();
        overrideRepo.delete(ov);

        ConfigAudit audit = new ConfigAudit();
        audit.setKey(key);
        audit.setPreviousValue(secret ? "***" : ov.getValue());
        audit.setNewValue(null);
        audit.setChangedAt(Instant.now());
        audit.setChangedBy(actor.getId());
        auditRepo.save(audit);

        source.remove(key);
        refresher.refresh();
        eventPublisher.publishEvent(new ConfigOverrideAppliedEvent(this, key, null));
    }

    private void validate(String key) {
        if (props.getTabooKeys().contains(key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Key is taboo");
        }
        if (props.getExposedPrefixes().stream().noneMatch(key::startsWith)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not exposable");
        }
    }

    private boolean isSecret(String key) {
        // Same kebab->dot normalization as ConfigCatalogService for relaxed-binding consistency.
        String normalized = key.replace('-', '.');
        return props.getSecretPatterns().stream()
                .map(Pattern::compile)
                .anyMatch(p -> p.matcher(normalized).matches());
    }
}
